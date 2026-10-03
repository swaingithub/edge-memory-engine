import Foundation
import SQLite3
import Security

final class DatabaseManager {
    static let shared = DatabaseManager()
    
    private var db: OpaquePointer?
    private let queue = DispatchQueue(label: "com.edgememory.dbqueue", qos: .userInitiated)
    
    private init() {
        openEncryptedDatabase()
    }
    
    private func openEncryptedDatabase() {
        let dbURL = AppGroupContainer.databaseURL
        let passphrase = KeychainHelper.getOrCreateDatabasePassphrase()
        
        if sqlite3_open(dbURL.path, &db) == SQLITE_OK {
            // Apply SQLCipher encryption key
            sqlite3_key(db, passphrase, Int32(passphrase.count))
            
            // Set WAL journal mode and foreign keys
            executeRaw(sql: "PRAGMA journal_mode = WAL;")
            executeRaw(sql: "PRAGMA synchronous = NORMAL;")
            executeRaw(sql: "PRAGMA foreign_keys = ON;")
            
            createSchemaIfNeeded()
        } else {
            fatalError("Failed to open SQLite database at \(dbURL.path)")
        }
    }
    
    private func createSchemaIfNeeded() {
        let schemaSQL = """
        CREATE TABLE IF NOT EXISTS event_log (
            event_id INTEGER PRIMARY KEY AUTOINCREMENT,
            entity_urn TEXT NOT NULL,
            timestamp INTEGER NOT NULL,
            action TEXT NOT NULL,
            source_app TEXT NOT NULL,
            raw_text TEXT NOT NULL,
            binary_embedding BLOB NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_entity_time ON event_log(entity_urn, timestamp ASC);
        CREATE INDEX IF NOT EXISTS idx_time ON event_log(timestamp DESC);
        CREATE VIRTUAL TABLE IF NOT EXISTS event_fts USING fts5(
            raw_text,
            content='event_log',
            content_rowid='event_id',
            tokenize='porter unicode61'
        );
        CREATE TRIGGER IF NOT EXISTS trg_event_ai AFTER INSERT ON event_log BEGIN
            INSERT INTO event_fts(rowid, raw_text) VALUES (new.event_id, new.raw_text);
        END;
        CREATE TABLE IF NOT EXISTS daily_summaries (
            day_date TEXT PRIMARY KEY,
            summary_text TEXT NOT NULL,
            summary_embedding BLOB NOT NULL
        );
        """
        executeRaw(sql: schemaSQL)
    }
    
    private func executeRaw(sql: String) {
        var errMsg: UnsafeMutablePointer<CChar>?
        if sqlite3_exec(db, sql, nil, nil, &errMsg) != SQLITE_OK {
            if let err = errMsg {
                print("SQLite Exec Error: \(String(cString: err))")
                sqlite3_free(errMsg)
            }
        }
    }
    
    func insertEvent(entityUrn: String, action: String = "SHARE_INPUT", sourceApp: String = "ShareExtension", rawText: String, binaryEmbedding: Data) {
        queue.sync {
            executeRaw(sql: "BEGIN IMMEDIATE TRANSACTION;")
            
            let sql = "INSERT INTO event_log (entity_urn, timestamp, action, source_app, raw_text, binary_embedding) VALUES (?, ?, ?, ?, ?, ?);"
            var statement: OpaquePointer?
            
            if sqlite3_prepare_v2(db, sql, -1, &statement, nil) == SQLITE_OK {
                let timestamp = Int64(Date().timeIntervalSince1970 * 1000)
                
                sqlite3_bind_text(statement, 1, (entityUrn as NSString).utf8String, -1, nil)
                sqlite3_bind_int64(statement, 2, timestamp)
                sqlite3_bind_text(statement, 3, (action as NSString).utf8String, -1, nil)
                sqlite3_bind_text(statement, 4, (sourceApp as NSString).utf8String, -1, nil)
                sqlite3_bind_text(statement, 5, (rawText as NSString).utf8String, -1, nil)
                
                binaryEmbedding.withUnsafeBytes { rawBufferPointer in
                    sqlite3_bind_blob(statement, 6, rawBufferPointer.baseAddress, Int32(binaryEmbedding.count), nil)
                }
                
                if sqlite3_step(statement) == SQLITE_DONE {
                    executeRaw(sql: "COMMIT TRANSACTION;")
                } else {
                    executeRaw(sql: "ROLLBACK TRANSACTION;")
                }
            } else {
                executeRaw(sql: "ROLLBACK TRANSACTION;")
            }
            sqlite3_finalize(statement)
        }
    }
    
    func getEventsByIds(eventIds: [Int64]) -> [Int64: String] {
        guard !eventIds.isEmpty else { return [:] }
        return queue.sync {
            var map: [Int64: String] = [:]
            let placeholders = eventIds.map { _ in "?" }.joined(separator: ",")
            let sql = "SELECT event_id, raw_text FROM event_log WHERE event_id IN (\(placeholders));"
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                for (idx, id) in eventIds.enumerated() {
                    sqlite3_bind_int64(stmt, Int32(idx + 1), id)
                }
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let id = sqlite3_column_int64(stmt, 0)
                    let text = String(cString: sqlite3_column_text(stmt, 1))
                    map[id] = text
                }
            }
            sqlite3_finalize(stmt)
            return map
        }
    }
    
    func runNightlyWALCheckpoint() async {
        queue.async {
            self.executeRaw(sql: "PRAGMA wal_checkpoint(TRUNCATE);")
        }
    }
    
    func searchFts(query: String, limit: Int = 50) -> [(eventId: Int64, urn: String, text: String, rank: Double)] {
        queue.sync {
            var results: [(eventId: Int64, urn: String, text: String, rank: Double)] = []
            let sql = """
            SELECT e.event_id, e.entity_urn, e.raw_text, fts.rank 
            FROM event_fts fts
            JOIN event_log e ON fts.rowid = e.event_id
            WHERE event_fts MATCH ?
            ORDER BY rank
            LIMIT ?;
            """
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                let sanitized = query.replacingOccurrences(of: "\"", with: "").trimmingCharacters(in: .whitespacesAndNewlines)
                let pattern = "\(sanitized)*"
                sqlite3_bind_text(stmt, 1, (pattern as NSString).utf8String, -1, nil)
                sqlite3_bind_int(stmt, 2, Int32(limit))
                
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let id = sqlite3_column_int64(stmt, 0)
                    let urn = String(cString: sqlite3_column_text(stmt, 1))
                    let text = String(cString: sqlite3_column_text(stmt, 2))
                    let rank = sqlite3_column_double(stmt, 3)
                    results.append((id, urn, text, rank))
                }
            }
            sqlite3_finalize(stmt)
            return results
        }
    }
    
    func loadBinaryCandidates() -> [(eventId: Int64, urn: String, blob: Data)] {
        queue.sync {
            var candidates: [(eventId: Int64, urn: String, blob: Data)] = []
            let sql = "SELECT event_id, entity_urn, binary_embedding FROM event_log ORDER BY timestamp DESC;"
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                while sqlite3_step(stmt) == SQLITE_ROW {
                    let id = sqlite3_column_int64(stmt, 0)
                    let urn = String(cString: sqlite3_column_text(stmt, 1))
                    if let blobPtr = sqlite3_column_blob(stmt, 2) {
                        let bytes = sqlite3_column_bytes(stmt, 2)
                        let data = Data(bytes: blobPtr, count: Int(bytes))
                        candidates.append((id, urn, data))
                    }
                }
            }
            sqlite3_finalize(stmt)
            return candidates
        }
    }
}

// Hardware-backed Keychain envelope for the SQLCipher passphrase
struct KeychainHelper {
    private static let tag = "com.edgememory.db_passphrase"
    private static let accessGroup = "group.com.edgememory.shared" // Matches your App Group
    
    static func getOrCreateDatabasePassphrase() -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: tag,
            kSecAttrAccessGroup as String: accessGroup,
            kSecReturnData as String: true
        ]
        
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data,
           let pass = String(data: data, encoding: .utf8) {
            return pass
        }
        
        // Generate 64 cryptographically secure random bytes
        var randomBytes = [UInt8](repeating: 0, count: 64)
        _ = SecRandomCopyBytes(kSecRandomDefault, 64, &randomBytes)
        let newPass = Data(randomBytes).base64EncodedString()
        
        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: tag,
            kSecAttrAccessGroup as String: accessGroup,
            kSecValueData as String: newPass.data(using: .utf8)!,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        SecItemAdd(addQuery as CFDictionary, nil)
        return newPass
    }
}
