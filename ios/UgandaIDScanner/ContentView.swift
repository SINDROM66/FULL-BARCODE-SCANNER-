import SwiftUI

struct ContentView: View {
    @StateObject private var recordManager = RecordManager()
    
    var body: some View {
        TabView {
            CaptureView(recordManager: recordManager)
                .tabItem {
                    Label("Capture", systemImage: "camera.viewfinder")
                }
            
            RecordsView(recordManager: recordManager)
                .tabItem {
                    Label("Records", systemImage: "list.bullet")
                }
        }
    }
}

struct CaptureView: View {
    @ObservedObject var recordManager: RecordManager
    @State private var scannedResult: ParseResponse?
    @State private var errorMessage: String?
    @State private var isShowingError = false
    @State private var isScannerActive = true // Controls scanner
    
    var body: some View {
        ZStack {
            if isScannerActive {
                BarcodeScannerView { payload in
                    sendToParser(payload: payload)
                }
                .ignoresSafeArea()
            } else {
                Color.black.ignoresSafeArea()
            }
            
            VStack {
                Text("Point camera at the PDF417 barcode on the BACK of the ID card")
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding()
                    .background(Color.black.opacity(0.7))
                    .cornerRadius(8)
                    .padding(.top, 40)
                
                Spacer()
            }
        }
        .sheet(item: $scannedResult, onDismiss: {
            isScannerActive = true
        }) { record in
            ResultSheet(record: record, recordManager: recordManager) {
                self.scannedResult = nil
                self.isScannerActive = true
            }
        }
        .alert("Scan Failed", isPresented: $isShowingError, presenting: errorMessage) { _ in
            Button("Try Again") {
                isScannerActive = true
            }
        } message: { msg in
            Text(msg)
        }
        .onAppear {
            isScannerActive = true
        }
        .onDisappear {
            isScannerActive = false
        }
    }
    
    func sendToParser(payload: String) {
        isScannerActive = false
        APIService.parseBarcode(payload: payload) { result in
            DispatchQueue.main.async {
                switch result {
                case .success(let record):
                    self.scannedResult = record
                case .failure(let error):
                    self.errorMessage = error.localizedDescription
                    self.isShowingError = true
                }
            }
        }
    }
}

struct ResultSheet: View {
    let record: ParseResponse
    @ObservedObject var recordManager: RecordManager
    var onDismiss: () -> Void
    
    @State private var phoneNumber: String = ""
    
    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Data Collection")) {
                    TextField("Phone Number", text: $phoneNumber)
                        .keyboardType(.phonePad)
                }
                
                Section(header: Text("Personal Details")) {
                    LabeledContent("Full Name", value: record.full_name)
                    LabeledContent("NIN", value: record.nin)
                    LabeledContent("Sex", value: record.sex)
                    LabeledContent("Date of Birth", value: "\(record.date_of_birth) (Age: \(record.age))")
                }
                
                Section(header: Text("Card Details")) {
                    LabeledContent("Card Number", value: record.card_number)
                    LabeledContent("Issued", value: record.issue_date)
                    LabeledContent("Expires", value: record.expiry_date)
                    LabeledContent("Status", value: record.is_expired ? "⚠️ EXPIRED" : "✅ Valid")
                }
                
                if !record.warnings.isEmpty {
                    Section(header: Text("Warnings")) {
                        ForEach(record.warnings, id: \.self) { warning in
                            Text("• \(warning)")
                                .foregroundColor(.orange)
                        }
                    }
                }
                
                Button(action: saveRecord) {
                    Text("Save Record")
                        .frame(maxWidth: .infinity)
                        .bold()
                }
                .buttonStyle(.borderedProminent)
            }
            .navigationTitle("Scanned Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        onDismiss()
                    }
                }
            }
        }
    }
    
    private func saveRecord() {
        let newRecord = SavedRecord(
            id: UUID(),
            parseResponse: record,
            phoneNumber: phoneNumber,
            timestamp: Date()
        )
        recordManager.saveRecord(newRecord)
        onDismiss()
    }
}

struct RecordsView: View {
    @ObservedObject var recordManager: RecordManager
    @State private var isShowingShareSheet = false
    @State private var exportURL: URL?
    
    var body: some View {
        NavigationView {
            List {
                ForEach(recordManager.records) { record in
                    VStack(alignment: .leading) {
                        Text(record.parseResponse.full_name)
                            .font(.headline)
                        Text("NIN: \(record.parseResponse.nin)")
                            .font(.subheadline)
                        Text("Phone: \(record.phoneNumber.isEmpty ? "N/A" : record.phoneNumber)")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                }
                .onDelete(perform: recordManager.deleteRecord)
            }
            .navigationTitle("Saved Records")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: exportRecords) {
                        Label("Export", systemImage: "square.and.arrow.up")
                    }
                    .disabled(recordManager.records.isEmpty)
                }
            }
            .sheet(isPresented: $isShowingShareSheet, onDismiss: {
                // Cleanup temp file if needed
                if let url = exportURL {
                    try? FileManager.default.removeItem(at: url)
                }
            }) {
                if let url = exportURL {
                    ShareSheet(activityItems: [url])
                }
            }
        }
    }
    
    private func exportRecords() {
        if let url = recordManager.exportToCSV() {
            self.exportURL = url
            self.isShowingShareSheet = true
        }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]
    
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }
    
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
