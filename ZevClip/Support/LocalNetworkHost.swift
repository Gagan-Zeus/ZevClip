import Darwin
import Foundation

enum LocalNetworkHost {
    static func currentPairingHost() -> String {
        primaryIPv4Address() ?? Host.current().localizedName ?? "localhost"
    }

    private static func primaryIPv4Address() -> String? {
        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let firstInterface = interfaces else {
            return nil
        }
        defer { freeifaddrs(interfaces) }

        var fallbackAddress: String?
        var interface = firstInterface

        while true {
            defer {
                if let next = interface.pointee.ifa_next {
                    interface = next
                }
            }

            let flags = Int32(interface.pointee.ifa_flags)
            let isUp = (flags & IFF_UP) == IFF_UP
            let isLoopback = (flags & IFF_LOOPBACK) == IFF_LOOPBACK

            if
                isUp,
                !isLoopback,
                let address = interface.pointee.ifa_addr,
                address.pointee.sa_family == UInt8(AF_INET),
                let ipv4Address = ipv4String(from: address)
            {
                let name = String(cString: interface.pointee.ifa_name)
                if name == "en0" || name == "en1" {
                    return ipv4Address
                }
                fallbackAddress = fallbackAddress ?? ipv4Address
            }

            guard interface.pointee.ifa_next != nil else {
                break
            }
        }

        return fallbackAddress
    }

    private static func ipv4String(from socketAddress: UnsafePointer<sockaddr>) -> String? {
        var address = socketAddress.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
            $0.pointee.sin_addr
        }

        var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
        guard inet_ntop(AF_INET, &address, &buffer, socklen_t(INET_ADDRSTRLEN)) != nil else {
            return nil
        }

        return String(cString: buffer)
    }
}
