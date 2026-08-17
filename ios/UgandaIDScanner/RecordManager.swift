import Foundation

struct SavedRecord: Codable, Identifiable {
    var id: UUID = UUID()
    let parseResponse: ParseResponse
    let phoneNumber: String
    let timestamp: Date
}

class RecordManager: ObservableObject {
    @Published var records: [SavedRecord] = []
    
    private let defaultsKey = "saved_records"
    
    init() {
        loadRecords()
    }
    
    func saveRecord(_ record: SavedRecord) {
        records.append(record)
        saveToDefaults()
    }
    
    func deleteRecord(at offsets: IndexSet) {
        records.remove(atOffsets: offsets)
        saveToDefaults()
    }
    
    private func loadRecords() {
        if let data = UserDefaults.standard.data(forKey: defaultsKey) {
            if let decoded = try? JSONDecoder().decode([SavedRecord].self, from: data) {
                self.records = decoded
            }
        }
    }
    
    private func saveToDefaults() {
        if let encoded = try? JSONEncoder().encode(records) {
            UserDefaults.standard.set(encoded, forKey: defaultsKey)
        }
    }
    
    func exportToCSV() -> URL? {
        var csvString = "Name,NIN,DOB,Sex,Phone Number,Timestamp\n"
        
        let formatter = ISO8601DateFormatter()
        
        for record in records {
            let name = record.parseResponse.full_name.replacingOccurrences(of: ",", with: " ")
            let nin = record.parseResponse.nin
            let dob = record.parseResponse.date_of_birth
            let sex = record.parseResponse.sex
            let phone = record.phoneNumber.replacingOccurrences(of: ",", with: " ")
            let date = formatter.string(from: record.timestamp)
            
            csvString += "\(name),\(nin),\(dob),\(sex),\(phone),\(date)\n"
        }
        
        let tempPath = FileManager.default.temporaryDirectory.appendingPathComponent("records.csv")
        do {
            try csvString.write(to: tempPath, atomically: true, encoding: .utf8)
            return tempPath
        } catch {
            print("Failed to create CSV: \(error)")
            return nil
        }
    }
}
