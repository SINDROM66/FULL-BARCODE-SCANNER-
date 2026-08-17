import Foundation

class RecordManager: ObservableObject {
    @Published var records: [ScannedRecord] = []
    private let saveKey = "SavedRecords"
    
    init() {
        loadRecords()
    }
    
    func saveRecord(_ record: ScannedRecord) {
        records.append(record)
        saveToDisk()
    }
    
    func loadRecords() {
        if let data = UserDefaults.standard.data(forKey: saveKey) {
            if let decoded = try? JSONDecoder().decode([ScannedRecord].self, from: data) {
                self.records = decoded
                return
            }
        }
        self.records = []
    }
    
    private func saveToDisk() {
        if let encoded = try? JSONEncoder().encode(records) {
            UserDefaults.standard.set(encoded, forKey: saveKey)
        }
    }
    
    func exportCSV() -> URL? {
        var csvString = "Timestamp,FullName,NIN,DOB,CardNumber,PhoneNumber\n"
        let formatter = ISO8601DateFormatter()
        for record in records {
            let name = record.fullName.replacingOccurrences(of: ",", with: " ")
            let dateStr = formatter.string(from: record.timestamp)
            csvString += "\(dateStr),\(name),\(record.nin),\(record.dateOfBirth),\(record.cardNumber),\(record.phoneNumber)\n"
        }
        
        let tempDirectory = FileManager.default.temporaryDirectory
        let fileURL = tempDirectory.appendingPathComponent("exported_records.csv")
        
        do {
            try csvString.write(to: fileURL, atomically: true, encoding: .utf8)
            return fileURL
        } catch {
            print("Failed to write CSV: \(error)")
            return nil
        }
    }
}
