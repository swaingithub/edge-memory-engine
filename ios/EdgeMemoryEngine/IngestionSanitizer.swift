import Foundation

struct IngestionSanitizer {
    
    // Pre-compiled regex patterns
    private static let creditCardRegex = try! NSRegularExpression(
        pattern: "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|6(?:011|5[0-9]{2})[0-9]{12}|3[47][0-9]{13})\\b"
    )
    
    private static let otpRegex = try! NSRegularExpression(
        pattern: "(?i)\\b(?:otp|code|verification|pin|passcode)\\s*(?:is|:|[-=])?\\s*(\\d{4,8})\\b"
    )
    
    // Strict spacing/separator constraints to avoid clobbering Unix timestamps
    private static let nationalIdRegex = try! NSRegularExpression(
        pattern: "\\b[2-9]\\d{3}[ -]\\d{4}[ -]\\d{4}\\b|\\b\\d{3}-\\d{2}-\\d{4}\\b"
    )
    
    private static let credentialsRegex = try! NSRegularExpression(
        pattern: "(?i)(?:password|passwd|pwd|cvv|secret|token|api[_-]?key)\\s*[:=]\\s*[^\\s,;]+"
    )

    static func sanitize(text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        
        var current = trimmed
        let fullRange = { NSRange(location: 0, length: current.utf16.count) }
        
        current = credentialsRegex.stringByReplacingMatches(in: current, range: fullRange(), withTemplate: "[REDACTED_CREDENTIAL]")
        current = creditCardRegex.stringByReplacingMatches(in: current, range: fullRange(), withTemplate: "[REDACTED_CARD]")
        current = nationalIdRegex.stringByReplacingMatches(in: current, range: fullRange(), withTemplate: "[REDACTED_ID]")
        current = otpRegex.stringByReplacingMatches(in: current, range: fullRange(), withTemplate: "[REDACTED_OTP]")
        
        // Collapse whitespace
        let cleaned = current.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
            
        return cleaned.isEmpty ? nil : cleaned
    }
}
