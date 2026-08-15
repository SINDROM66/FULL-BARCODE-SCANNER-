import Foundation

struct ParseRequest: Codable {
    let payload: String
}

struct ParseResponse: Codable, Identifiable {
    let id = UUID()
    let surname: String
    let given_name: String
    let other_name: String
    let full_name: String
    let date_of_birth: String
    let issue_date: String
    let expiry_date: String
    let nin: String
    let sex: String
    let card_number: String
    let age: Int
    let is_expired: Bool
    let fingerprint: FingerprintModel
    let warnings: [String]
}

struct FingerprintModel: Codable {
    let finger_index: Int?
    let minutiae_count: Int?
    let minutiae_bytes: Int?
    let sealed_block_bytes: Int?
}

class APIService {
    // SIMULATOR (backend on same Mac): http://127.0.0.1:8000
    // PHYSICAL DEVICE (same WiFi): http://YOUR_MAC_IP:8000
    static let baseURL = "http://127.0.0.1:8000"

    static func parseBarcode(payload: String, completion: @escaping (Result<ParseResponse, Error>) -> Void) {
        guard let url = URL(string: "\(baseURL)/parse") else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")

        let body = ParseRequest(payload: payload)
        request.httpBody = try? JSONEncoder().encode(body)

        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error = error {
                completion(.failure(error))
                return
            }
            guard let data = data else {
                completion(.failure(NSError(domain: "No data", code: -1)))
                return
            }
            do {
                let result = try JSONDecoder().decode(ParseResponse.self, from: data)
                completion(.success(result))
            } catch {
                completion(.failure(error))
            }
        }.resume()
    }
}
