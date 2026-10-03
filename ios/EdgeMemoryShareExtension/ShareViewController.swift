import UIKit
import Social
import UniformTypeIdentifiers

class ShareViewController: SLComposeServiceViewController {

    override func isContentValid() -> Bool {
        return !contentText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    override func didSelectPost() {
        guard let item = extensionContext?.inputItems.first as? NSExtensionItem,
              let provider = item.attachments?.first else {
            self.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
            return
        }

        if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { [weak self] (textData, error) in
                if let rawText = textData as? String {
                    self?.processAndStore(text: rawText)
                }
                self?.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
            }
        } else {
            self.extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
        }
    }

    private func processAndStore(text: String) {
        // 1. Sanitize text (Redact credentials, cards, IDs)
        guard let sanitized = IngestionSanitizer.sanitize(text: text) else { return }

        // 2. Generate 512 floats via CoreML model or on-device NLEmbedding
        let floatVector = EmbeddingManager.shared.embed(text: sanitized)

        // 3. 1-bit pack
        let binaryBlob = BitPacker.pack(embeddings: floatVector)

        // 4. Insert into shared SQLCipher database in group container
        DatabaseManager.shared.insertEvent(
            entityUrn: "urn:ios:share_extension",
            rawText: sanitized,
            binaryEmbedding: binaryBlob
        )
    }
}
