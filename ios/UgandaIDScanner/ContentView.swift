import SwiftUI

struct ContentView: View {
    @State private var scannedResult: ParseResponse?
    @State private var errorMessage: String?
    @State private var isShowingResult = false
    @State private var isShowingError = false

    var body: some View {
        ZStack {
            BarcodeScannerView { payload in
                sendToParser(payload: payload)
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
        .sheet(item: $scannedResult) { record in
            ResultSheet(record: record)
        }
        .alert("Scan Failed", isPresented: $isShowingError, presenting: errorMessage) { _ in
            Button("Try Again") { }
        } message: { msg in
            Text(msg)
        }
    }

    func sendToParser(payload: String) {
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

    var body: some View {
        NavigationView {
            List {
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
            }
            .navigationTitle("ID Scanned")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
