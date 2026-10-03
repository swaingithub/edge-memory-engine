// Shared Database Container Resolver
import Foundation

struct AppGroupContainer {
    static let groupIdentifier = "group.com.edgememory.shared"
    
    static var databaseURL: URL {
        guard let containerURL = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: groupIdentifier
        ) else {
            fatalError("Failed to resolve shared App Group directory.")
        }
        return containerURL.appendingPathComponent("edge_memory_encrypted.db")
    }
}
