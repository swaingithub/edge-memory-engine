import SwiftUI

struct MemorySearchContentView: View {
    @State private var query = ""
    @State private var hits: [RetrievedHit] = []
    @State private var latencyMs: Int = 0
    @State private var isSearching = false
    
    private let retriever = CascadedRetriever()
    
    var body: some View {
        NavigationStack {
            VStack {
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.gray)
                    TextField("Search memory (e.g., ticket, meeting, order)", text: $query)
                        .textFieldStyle(.plain)
                        .onSubmit { performSearch() }
                    if !query.isEmpty {
                        Button(action: { query = ""; hits = [] }) {
                            Image(systemName: "xmark.circle.fill").foregroundColor(.gray)
                        }
                    }
                }
                .padding(12)
                .background(Color(.secondarySystemBackground))
                .cornerRadius(10)
                .padding(.horizontal)
                
                if latencyMs > 0 {
                    HStack {
                        Text("Retrieved in \(latencyMs) ms")
                            .font(.caption)
                            .foregroundColor(.accentColor)
                            .fontWeight(.semibold)
                        Spacer()
                    }
                    .padding(.horizontal)
                    .padding(.top, 4)
                }
                
                if isSearching {
                    Spacer()
                    ProgressView()
                    Spacer()
                } else {
                    List(hits, id: \.eventId) { hit in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(hit.urn)
                                    .font(.caption)
                                    .fontWeight(.bold)
                                    .foregroundColor(.secondary)
                                Spacer()
                                Text(String(format: "Score: %.3f", hit.score))
                                    .font(.caption2)
                                    .foregroundColor(.gray)
                            }
                            Text(hit.text)
                                .font(.body)
                                .lineLimit(3)
                        }
                        .padding(.vertical, 4)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("Edge Memory Engine")
        }
    }
    
    private func performSearch() {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        isSearching = true
        let start = DispatchTime.now()
        
        Task {
            let results = await retriever.retrieve(query: query)
            let end = DispatchTime.now()
            let nanoTime = end.uptimeNanoseconds - start.uptimeNanoseconds
            
            await MainActor.run {
                self.hits = results
                self.latencyMs = Int(nanoTime / 1_000_000)
                self.isSearching = false
            }
        }
    }
}
