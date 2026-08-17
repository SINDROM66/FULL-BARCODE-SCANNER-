import SwiftUI

struct ContentView: View {
    @StateObject private var recordManager = RecordManager()
    @State private var scannedRecord: ScannedRecord?
    @State private var showingSaveSheet = false
    @State private var errorMessage: String?
    @State private var isShowingError = false

    var body: some View {
        TabView {
            CaptureTab(scannedRecord: $scannedRecord, showingSaveSheet: $showingSaveSheet, errorMessage: $errorMessage, isShowingError: $isShowingError)
                .tabItem {
                    Label("Capture", systemImage: "camera")
                }
            
            RecordsTab()
                .environmentObject(recordManager)
                .tabItem {
                    Label("Records", systemImage: "list.bullet")
                }
        }
        .sheet(isPresented: $showingSaveSheet) {
            if let record = scannedRecord {
                SaveRecordSheet(record: record, recordManager: recordManager) {
                    showingSaveSheet = false
                    scannedRecord = nil
                }
            }
        }
        .alert("Scan Failed", isPresented: $isShowingError, presenting: errorMessage) { _ in
            Button("OK") { }
        } message: { msg in
            Text(msg)
        }
    }
}

struct CaptureTab: View {
    @Binding var scannedRecord: ScannedRecord?
    @Binding var showingSaveSheet: Bool
    @Binding var errorMessage: String?
    @Binding var isShowingError: Bool
    
    var body: some View {
        ZStack {
            BarcodeScannerView { payload in
                if showingSaveSheet { return }
                if let record = UgandaIdParser.parse(payload: payload) {
                    self.scannedRecord = record
                    self.showingSaveSheet = true
                } else {
                    self.errorMessage = "Invalid barcode format."
                    self.isShowingError = true
                }
            }
            .ignoresSafeArea()
            
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
    }
}

struct SaveRecordSheet: View {
    @State var record: ScannedRecord
    var recordManager: RecordManager
    var onDismiss: () -> Void
    
    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("Parsed Details")) {
                    LabeledContent("Full Name", value: record.fullName)
                    LabeledContent("NIN", value: record.nin)
                    LabeledContent("Date of Birth", value: record.dateOfBirth)
                    LabeledContent("Card Number", value: record.cardNumber)
                }
                
                Section(header: Text("Data Collection")) {
                    TextField("Phone Number", text: $record.phoneNumber)
                        .keyboardType(.phonePad)
                }
            }
            .navigationTitle("Save Record")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        onDismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        recordManager.saveRecord(record)
                        onDismiss()
                    }
                }
            }
        }
    }
}

struct RecordsTab: View {
    @EnvironmentObject var recordManager: RecordManager
    @State private var showingExport = false
    @State private var exportURL: URL?

    var body: some View {
        NavigationView {
            List {
                ForEach(recordManager.records) { record in
                    VStack(alignment: .leading) {
                        Text(record.fullName).font(.headline)
                        Text("NIN: \(record.nin)").font(.subheadline)
                        if !record.phoneNumber.isEmpty {
                            Text("Phone: \(record.phoneNumber)").font(.caption).foregroundColor(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Saved Records")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button(action: {
                        exportURL = recordManager.exportCSV()
                        if exportURL != nil {
                            showingExport = true
                        }
                    }) {
                        Label("Export", systemImage: "square.and.arrow.up")
                    }
                }
            }
            .sheet(isPresented: $showingExport, content: {
                if let url = exportURL {
                    ActivityViewController(activityItems: [url])
                }
            })
        }
    }
}

struct ActivityViewController: UIViewControllerRepresentable {
    var activityItems: [Any]
    var applicationActivities: [UIActivity]? = nil

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: activityItems, applicationActivities: applicationActivities)
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
