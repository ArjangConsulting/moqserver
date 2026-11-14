import Foundation
import NIOSSL
import Vapor

/// PEM certificate chain and private key for serving HTTPS (`serve --tls-cert/--tls-key`), for
/// apps that enforce HTTPS (App Transport Security, Android network security config).
public struct TLSFiles: Sendable, Equatable {
    public let certificatePath: String
    public let privateKeyPath: String

    public init(certificatePath: String, privateKeyPath: String) {
        self.certificatePath = (certificatePath as NSString).expandingTildeInPath
        self.privateKeyPath = (privateKeyPath as NSString).expandingTildeInPath
    }

    /// Loads both files; throws `TLSFilesError` naming the file that could not be read.
    func serverConfiguration() throws -> TLSConfiguration {
        let certificates: [NIOSSLCertificate]
        do {
            certificates = try NIOSSLCertificate.fromPEMFile(certificatePath)
        } catch {
            throw TLSFilesError.unreadableCertificate(certificatePath, "\(error)")
        }
        guard !certificates.isEmpty else {
            throw TLSFilesError.unreadableCertificate(certificatePath, "no certificates")
        }
        let privateKey: NIOSSLPrivateKey
        do {
            privateKey = try NIOSSLPrivateKey(file: privateKeyPath, format: .pem)
        } catch {
            throw TLSFilesError.unreadablePrivateKey(privateKeyPath, "\(error)")
        }
        return TLSConfiguration.makeServerConfiguration(
            certificateChain: certificates.map { .certificate($0) }, privateKey: .privateKey(privateKey))
    }
}

public enum TLSFilesError: Error, CustomStringConvertible, Equatable {
    case unreadableCertificate(String, String)
    case unreadablePrivateKey(String, String)

    public var description: String {
        switch self {
        case .unreadableCertificate(let path, let detail):
            return "Could not load TLS certificate chain (PEM) from \(path): \(detail)"
        case .unreadablePrivateKey(let path, let detail):
            return "Could not load TLS private key (PEM) from \(path): \(detail)"
        }
    }
}
