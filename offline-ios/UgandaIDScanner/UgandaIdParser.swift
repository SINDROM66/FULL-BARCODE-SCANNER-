import Foundation

struct ScannedRecord: Identifiable, Codable {
    var id = UUID()
    var surname: String
    var givenName: String
    var otherName: String
    var dateOfBirth: String
    var nin: String
    var cardNumber: String
    var phoneNumber: String
    var timestamp: Date
    
    var fullName: String {
        let parts = [surname, givenName, otherName].filter { !$0.isEmpty }
        return parts.joined(separator: " ")
    }
}

class UgandaIdParser {
    static func parse(payload: String) -> ScannedRecord? {
        let fngSplit = payload.components(separatedBy: "[FNG]")
        guard let firstPart = fngSplit.first else { return nil }
        
        let components = firstPart.components(separatedBy: ";")
        if components.count <= 7 { return nil }
        
        let surname = decodeBase64(components[0])
        let givenName = decodeBase64(components[1])
        let otherName = decodeBase64(components[2])
        let dob = components[3]
        let nin = components[6]
        let cardNumber = components[7]
        
        return ScannedRecord(
            surname: surname,
            givenName: givenName,
            otherName: otherName,
            dateOfBirth: dob,
            nin: nin,
            cardNumber: cardNumber,
            phoneNumber: "",
            timestamp: Date()
        )
    }
    
    private static func decodeBase64(_ string: String) -> String {
        guard let data = Data(base64Encoded: string),
              let decoded = String(data: data, encoding: .utf8) else {
            return string
        }
        return decoded
    }
}
