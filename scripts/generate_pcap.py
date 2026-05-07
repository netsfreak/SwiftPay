#!/usr/bin/env python3
"""
PCAP Generator for Personal P2P Payment System (PPPS)
======================================================
Generates a realistic PCAP file demonstrating:
  - Real bidirectional TCP client <-> server communication
  - Full TCP 3-way handshakes (SYN -> SYN/ACK -> ACK)
  - HTTP/1.1 request/response cycles with JSON payloads
  - Multiple complete sessions covering all PPPS API flows:
      1. User Registration   POST /api/v1/auth/register
      2. User Login          POST /api/v1/auth/login
      3. User Profile Create POST /api/v1/users
      4. Wallet Balance      GET  /api/v1/wallets/{id}/balance
      5. Deposit Funds       POST /api/v1/funding/deposits
      6. P2P Transfer        POST /api/v1/transfers
      7. Withdrawal          POST /api/v1/funding/withdrawals
      8. Failed Login        POST /api/v1/auth/login  (400 response)
      9. Duplicate Transfer  POST /api/v1/transfers   (idempotency check)
     10. DNS resolution       www.ppps-api.local -> 10.0.1.10
  - Proper TCP FIN/ACK teardown after every session
  - Realistic MAC/IP addresses (no loopback)
  - Realistic timestamps with inter-packet gaps
"""

import json
import os
import random
import struct
import time
import uuid

# ---------------------------------------------------------------------------
# Low-level PCAP writer (no third-party library needed beyond stdlib)
# ---------------------------------------------------------------------------
PCAP_GLOBAL_HEADER = struct.pack(
    "<IHHiIII",
    0xA1B2C3D4,  # magic number
    2,
    4,  # version major, minor
    0,  # GMT offset
    0,  # timestamp accuracy
    65535,  # snaplen
    1,  # link-type: ETHERNET
)


def _ts_bytes(ts_float):
    sec = int(ts_float)
    usec = int((ts_float - sec) * 1_000_000)
    return struct.pack("<II", sec, usec)


def _pcap_record(ts_float, raw_bytes):
    length = len(raw_bytes)
    return _ts_bytes(ts_float) + struct.pack("<II", length, length) + raw_bytes


# ---------------------------------------------------------------------------
# Ethernet / IP / TCP / UDP / DNS helpers (hand-crafted, no Scapy)
# ---------------------------------------------------------------------------
def _checksum(data: bytes) -> int:
    if len(data) % 2:
        data += b"\x00"
    s = 0
    for i in range(0, len(data), 2):
        w = (data[i] << 8) + data[i + 1]
        s += w
    while s >> 16:
        s = (s & 0xFFFF) + (s >> 16)
    return ~s & 0xFFFF


def _eth(src_mac: bytes, dst_mac: bytes, ethertype: int, payload: bytes) -> bytes:
    return src_mac + dst_mac + struct.pack("!H", ethertype) + payload


def _ipv4(
    src: str, dst: str, proto: int, payload: bytes, identification: int = 1
) -> bytes:
    ihl = 5
    version = 4
    tos = 0
    total_len = 20 + len(payload)
    flags_offset = 0x4000  # Don't Fragment
    ttl = 64
    header = struct.pack(
        "!BBHHHBBH4s4s",
        (version << 4) | ihl,
        tos,
        total_len,
        identification & 0xFFFF,
        flags_offset,
        ttl,
        proto,
        0,  # checksum placeholder
        _ip_bytes(src),
        _ip_bytes(dst),
    )
    cs = _checksum(header)
    header = header[:10] + struct.pack("!H", cs) + header[12:]
    return header + payload


def _ip_bytes(addr: str) -> bytes:
    return bytes(int(x) for x in addr.split("."))


def _tcp(
    sport: int,
    dport: int,
    seq: int,
    ack: int,
    flags: int,
    payload: bytes,
    src_ip: str,
    dst_ip: str,
    window: int = 65535,
) -> bytes:
    offset = 5  # 20-byte header, no options
    header = struct.pack(
        "!HHIIBBHHH",
        sport,
        dport,
        seq & 0xFFFFFFFF,
        ack & 0xFFFFFFFF,
        (offset << 4),
        flags,
        window,
        0,  # checksum placeholder
        0,  # urgent pointer
    )
    # Pseudo-header for checksum
    pseudo = (
        _ip_bytes(src_ip)
        + _ip_bytes(dst_ip)
        + struct.pack("!BBH", 0, 6, len(header) + len(payload))
    )
    cs = _checksum(pseudo + header + payload)
    header = header[:16] + struct.pack("!H", cs) + header[18:]
    return header + payload


def _udp(sport: int, dport: int, payload: bytes, src_ip: str, dst_ip: str) -> bytes:
    length = 8 + len(payload)
    header = struct.pack("!HHHH", sport, dport, length, 0)
    pseudo = _ip_bytes(src_ip) + _ip_bytes(dst_ip) + struct.pack("!BBH", 0, 17, length)
    cs = _checksum(pseudo + header + payload)
    header = header[:6] + struct.pack("!H", cs) + header[8:]
    return header + payload


def _dns_query(txid: int, qname: str) -> bytes:
    """Minimal DNS A-record query."""
    header = struct.pack("!HHHHHH", txid, 0x0100, 1, 0, 0, 0)
    question = b""
    for label in qname.split("."):
        encoded = label.encode()
        question += struct.pack("B", len(encoded)) + encoded
    question += b"\x00" + struct.pack("!HH", 1, 1)  # QTYPE A, QCLASS IN
    return header + question


def _dns_response(txid: int, qname: str, answer_ip: str) -> bytes:
    """Minimal DNS A-record response."""
    header = struct.pack("!HHHHHH", txid, 0x8180, 1, 1, 0, 0)
    question = b""
    for label in qname.split("."):
        encoded = label.encode()
        question += struct.pack("B", len(encoded)) + encoded
    question += b"\x00" + struct.pack("!HH", 1, 1)
    # Answer: name pointer + type + class + ttl + rdlength + rdata
    answer = (
        struct.pack("!HHHI", 0xC00C, 1, 1, 300)
        + struct.pack("!H", 4)
        + _ip_bytes(answer_ip)
    )
    return header + question + answer


# ---------------------------------------------------------------------------
# Addresses
# ---------------------------------------------------------------------------
CLIENT_MAC = bytes.fromhex("AABBCCDDEEFF")
SERVER_MAC = bytes.fromhex("001122334455")
DNS_MAC = bytes.fromhex("001122334400")

CLIENT_IP = "10.0.0.50"  # Browser / mobile client
GATEWAY_IP = "10.0.1.10"  # API Gateway  :8080
AUTH_IP = "10.0.1.11"  # Auth Service :9081
USER_IP = "10.0.1.12"  # User Service :9082
WALLET_IP = "10.0.1.13"  # Wallet Service :9083
PAYMENT_IP = "10.0.1.14"  # Payment Service :9084
FUNDING_IP = "10.0.1.15"  # Funding Service :9085
DNS_IP = "10.0.0.1"  # DNS Resolver

GATEWAY_PORT = 8080
AUTH_PORT = 9081
USER_PORT = 9082
WALLET_PORT = 9083
PAYMENT_PORT = 9084
FUNDING_PORT = 9085

# TCP Flags
SYN = 0x02
SYN_ACK = 0x12
ACK = 0x10
PSH_ACK = 0x18
FIN_ACK = 0x11
RST_ACK = 0x14

# Pre-built UUIDs for stable references
USER_A_ID = str(uuid.UUID("aaaaaaaa-0000-0000-0000-000000000001"))
USER_B_ID = str(uuid.UUID("bbbbbbbb-0000-0000-0000-000000000002"))
WALLET_A_ID = str(uuid.UUID("cccccccc-0000-0000-0000-000000000003"))
WALLET_B_ID = str(uuid.UUID("dddddddd-0000-0000-0000-000000000004"))
TX_ID_1 = str(uuid.UUID("eeeeeeee-0000-0000-0000-000000000005"))
TX_ID_2 = str(uuid.UUID("ffffffff-0000-0000-0000-000000000006"))
TOKEN_A = "token-" + str(uuid.UUID("11111111-aaaa-0000-0000-000000000001"))
TOKEN_B = "token-" + str(uuid.UUID("22222222-bbbb-0000-0000-000000000002"))


# ---------------------------------------------------------------------------
# Packet builder / session manager
# ---------------------------------------------------------------------------
class PcapBuilder:
    def __init__(self):
        self.records = []
        self.ts = time.mktime(time.strptime("2025-01-15 09:00:00", "%Y-%m-%d %H:%M:%S"))
        self._id_counter = 1

    def _next_id(self):
        v = self._id_counter
        self._id_counter += 1
        return v

    def _tick(self, delta=0.001):
        self.ts += delta
        return self.ts

    def _eth_packet(self, src_mac, dst_mac, src_ip, dst_ip, proto_payload, proto=6):
        ip = _ipv4(src_ip, dst_ip, proto, proto_payload, self._next_id())
        eth = _eth(src_mac, dst_mac, 0x0800, ip)
        return eth

    def _add(self, raw, delta=0.001):
        self.ts += delta
        self.records.append(_pcap_record(self.ts, raw))

    # -- DNS ------------------------------------------------------------------
    def dns_session(self, qname: str, answer_ip: str, src_port: int = None):
        """Client queries DNS, resolver replies."""
        sport = src_port or random.randint(50000, 60000)
        txid = random.randint(1, 0xFFFF)

        # Query
        dns_q = _dns_query(txid, qname)
        udp_q = _udp(sport, 53, dns_q, CLIENT_IP, DNS_IP)
        ip_q = _ipv4(CLIENT_IP, DNS_IP, 17, udp_q, self._next_id())
        eth_q = _eth(CLIENT_MAC, DNS_MAC, 0x0800, ip_q)
        self._add(eth_q, 0.001)

        # Response
        dns_r = _dns_response(txid, qname, answer_ip)
        udp_r = _udp(53, sport, dns_r, DNS_IP, CLIENT_IP)
        ip_r = _ipv4(DNS_IP, CLIENT_IP, 17, udp_r, self._next_id())
        eth_r = _eth(DNS_MAC, CLIENT_MAC, 0x0800, ip_r)
        self._add(eth_r, 0.002)

    # -- TCP session helpers --------------------------------------------------
    def _build_tcp(
        self,
        src_ip,
        src_mac,
        dst_ip,
        dst_mac,
        sport,
        dport,
        seq,
        ack,
        flags,
        payload=b"",
    ):
        tcp_seg = _tcp(sport, dport, seq, ack, flags, payload, src_ip, dst_ip)
        ip_pkt = _ipv4(src_ip, dst_ip, 6, tcp_seg, self._next_id())
        eth_frm = _eth(src_mac, dst_mac, 0x0800, ip_pkt)
        return eth_frm

    def http_session(
        self,
        src_ip,
        src_mac,
        dst_ip,
        dst_mac,
        sport,
        dport,
        method,
        path,
        req_headers,
        req_body,
        resp_status,
        resp_headers,
        resp_body,
        base_seq_c=None,
        base_seq_s=None,
    ):
        """
        Simulate a complete HTTP/1.1 session:
          SYN -> SYN/ACK -> ACK -> [PSH/ACK request] -> ACK ->
          [PSH/ACK response] -> ACK -> FIN/ACK -> FIN/ACK -> ACK
        Returns (client_seq_end, server_seq_end).
        """
        seq_c = base_seq_c or random.randint(1_000_000, 2_000_000)
        seq_s = base_seq_s or random.randint(3_000_000, 4_000_000)

        def c2s(seq, ack, flags, payload=b"", delta=0.001):
            frm = self._build_tcp(
                src_ip, src_mac, dst_ip, dst_mac, sport, dport, seq, ack, flags, payload
            )
            self._add(frm, delta)

        def s2c(seq, ack, flags, payload=b"", delta=0.001):
            frm = self._build_tcp(
                dst_ip, dst_mac, src_ip, src_mac, dport, sport, seq, ack, flags, payload
            )
            self._add(frm, delta)

        # ---- 3-way handshake ----
        c2s(seq_c, 0, SYN, delta=0.005)
        seq_c += 1
        s2c(seq_s, seq_c, SYN_ACK, delta=0.003)
        seq_s += 1
        c2s(seq_c, seq_s, ACK, delta=0.001)

        # ---- HTTP Request ----
        http_req = _build_http_request(
            method, path, dst_ip, dport, req_headers, req_body
        )
        c2s(seq_c, seq_s, PSH_ACK, http_req, delta=0.002)
        seq_c += len(http_req)
        s2c(seq_s, seq_c, ACK, delta=0.001)

        # ---- HTTP Response ----
        http_resp = _build_http_response(resp_status, resp_headers, resp_body)
        s2c(seq_s, seq_c, PSH_ACK, http_resp, delta=0.015)
        seq_s += len(http_resp)
        c2s(seq_c, seq_s, ACK, delta=0.001)

        # ---- Teardown ----
        c2s(seq_c, seq_s, FIN_ACK, delta=0.002)
        seq_c += 1
        s2c(seq_s, seq_c, FIN_ACK, delta=0.001)
        seq_s += 1
        c2s(seq_c, seq_s, ACK, delta=0.001)

        return seq_c, seq_s

    def write(self, path: str):
        with open(path, "wb") as f:
            f.write(PCAP_GLOBAL_HEADER)
            for rec in self.records:
                f.write(rec)
        print(f"[+] Written {len(self.records)} packets to {path}")


# ---------------------------------------------------------------------------
# HTTP message formatters
# ---------------------------------------------------------------------------
def _build_http_request(method, path, host, port, extra_headers, body):
    if isinstance(body, (dict, list)):
        body_str = json.dumps(body, separators=(",", ":"))
    else:
        body_str = body or ""
    body_bytes = body_str.encode()

    headers = {
        "Host": f"{host}:{port}",
        "User-Agent": "PPPS-Client/1.0",
        "Accept": "application/json",
        "Connection": "close",
    }
    if body_bytes:
        headers["Content-Type"] = "application/json"
        headers["Content-Length"] = str(len(body_bytes))
    headers.update(extra_headers or {})

    lines = [f"{method} {path} HTTP/1.1"]
    for k, v in headers.items():
        lines.append(f"{k}: {v}")
    lines.append("")
    lines.append("")
    raw = "\r\n".join(lines).encode()
    if body_bytes:
        raw = raw[:-2] + body_bytes + b"\r\n"
    return raw


def _build_http_response(status_line, extra_headers, body):
    if isinstance(body, (dict, list)):
        body_str = json.dumps(body, separators=(",", ":"))
    else:
        body_str = body or ""
    body_bytes = body_str.encode()

    headers = {
        "Content-Type": "application/json",
        "Content-Length": str(len(body_bytes)),
        "Connection": "close",
        "Server": "PPPS-Gateway/1.0",
        "X-Request-Id": str(uuid.uuid4()),
    }
    headers.update(extra_headers or {})

    lines = [f"HTTP/1.1 {status_line}"]
    for k, v in headers.items():
        lines.append(f"{k}: {v}")
    lines.append("")
    lines.append("")
    raw = "\r\n".join(lines).encode()
    if body_bytes:
        raw = raw[:-2] + body_bytes + b"\r\n"
    return raw


# ---------------------------------------------------------------------------
# Scenario builder
# ---------------------------------------------------------------------------
def build_pcap(output_path: str):
    pcap = PcapBuilder()

    # =========================================================================
    # 0.  DNS resolution – client resolves ppps-api.local before first call
    # =========================================================================
    pcap.ts += 0.1
    print("[*] Generating DNS resolution packets …")
    pcap.dns_session("ppps-api.local", GATEWAY_IP, src_port=54321)
    pcap.dns_session("ppps-auth.local", AUTH_IP, src_port=54322)

    # =========================================================================
    # 1.  User A – Registration  (Client -> API Gateway :8080)
    # =========================================================================
    pcap.ts += 0.5
    print("[*] Generating Session 1: User-A Registration …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52001,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/auth/register",
        req_headers={"X-Request-Id": "req-001"},
        req_body={
            "phoneNumber": "+1-555-100-0001",
            "password": "SecurePass@2025",
            "fullName": "Alice Johnson",
        },
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-001"},
        resp_body={"token": TOKEN_A, "phoneNumber": "+1-555-100-0001", "role": "USER"},
    )

    # =========================================================================
    # 2.  User B – Registration
    # =========================================================================
    pcap.ts += 0.3
    print("[*] Generating Session 2: User-B Registration …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52002,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/auth/register",
        req_headers={"X-Request-Id": "req-002"},
        req_body={
            "phoneNumber": "+1-555-200-0002",
            "password": "SecurePass@2025!",
            "fullName": "Bob Smith",
        },
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-002"},
        resp_body={"token": TOKEN_B, "phoneNumber": "+1-555-200-0002", "role": "USER"},
    )

    # =========================================================================
    # 3.  User A – Login
    # =========================================================================
    pcap.ts += 0.4
    print("[*] Generating Session 3: User-A Login …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52003,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/auth/login",
        req_headers={"X-Request-Id": "req-003"},
        req_body={"phoneNumber": "+1-555-100-0001", "password": "SecurePass@2025"},
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-003"},
        resp_body={"token": TOKEN_A, "phoneNumber": "+1-555-100-0001", "role": "USER"},
    )

    # =========================================================================
    # 4.  FAILED Login – wrong password (HTTP 400)
    # =========================================================================
    pcap.ts += 0.3
    print("[*] Generating Session 4: Failed Login (400 Bad Request) …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52004,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/auth/login",
        req_headers={"X-Request-Id": "req-004"},
        req_body={"phoneNumber": "+1-555-100-0001", "password": "WrongPassword!"},
        resp_status="400 Bad Request",
        resp_headers={"X-Trace-Id": "trace-004"},
        resp_body="Invalid credentials",
    )

    # =========================================================================
    # 5.  Create User Profile A (User Service)
    # =========================================================================
    pcap.ts += 0.4
    print("[*] Generating Session 5: Create User Profile A …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52005,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/users",
        req_headers={"Authorization": f"Bearer {TOKEN_A}", "X-Request-Id": "req-005"},
        req_body={
            "userId": USER_A_ID,
            "fullName": "Alice Johnson",
            "phoneNumber": "+1-555-100-0001",
            "email": "alice@ppps.io",
        },
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-005"},
        resp_body={
            "userId": USER_A_ID,
            "fullName": "Alice Johnson",
            "phoneNumber": "+1-555-100-0001",
            "email": "alice@ppps.io",
        },
    )

    # =========================================================================
    # 6.  Create User Profile B
    # =========================================================================
    pcap.ts += 0.2
    print("[*] Generating Session 6: Create User Profile B …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52006,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/users",
        req_headers={"Authorization": f"Bearer {TOKEN_B}", "X-Request-Id": "req-006"},
        req_body={
            "userId": USER_B_ID,
            "fullName": "Bob Smith",
            "phoneNumber": "+1-555-200-0002",
            "email": "bob@ppps.io",
        },
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-006"},
        resp_body={
            "userId": USER_B_ID,
            "fullName": "Bob Smith",
            "phoneNumber": "+1-555-200-0002",
            "email": "bob@ppps.io",
        },
    )

    # =========================================================================
    # 7.  Check Wallet Balance – Alice (Wallet Service via Gateway)
    # =========================================================================
    pcap.ts += 0.3
    print("[*] Generating Session 7: GET Wallet Balance (Alice) …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52007,
        dport=GATEWAY_PORT,
        method="GET",
        path=f"/api/v1/wallets/{WALLET_A_ID}/balance",
        req_headers={"Authorization": f"Bearer {TOKEN_A}", "X-Request-Id": "req-007"},
        req_body=None,
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-007"},
        resp_body={"walletId": WALLET_A_ID, "balance": "0.00"},
    )

    # =========================================================================
    # 8.  Deposit Funds – Alice deposits $500.00 (Funding Service)
    # =========================================================================
    pcap.ts += 0.5
    print("[*] Generating Session 8: Deposit $500 into Alice's Wallet …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52008,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/funding/deposits",
        req_headers={
            "Authorization": f"Bearer {TOKEN_A}",
            "X-Request-Id": "req-008",
            "Idempotency-Key": "deposit-alice-001",
        },
        req_body={
            "walletId": WALLET_A_ID,
            "amount": "500.00",
            "reference": "BANK-TRANSFER-REF-8821",
        },
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-008"},
        resp_body={"transactionId": TX_ID_1, "status": "COMPLETED", "type": "DEPOSIT"},
    )

    # =========================================================================
    # 9.  Deposit Funds – Bob deposits $200.00
    # =========================================================================
    pcap.ts += 0.4
    print("[*] Generating Session 9: Deposit $200 into Bob's Wallet …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52009,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/funding/deposits",
        req_headers={
            "Authorization": f"Bearer {TOKEN_B}",
            "X-Request-Id": "req-009",
            "Idempotency-Key": "deposit-bob-001",
        },
        req_body={
            "walletId": WALLET_B_ID,
            "amount": "200.00",
            "reference": "BANK-TRANSFER-REF-9942",
        },
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-009"},
        resp_body={"transactionId": TX_ID_2, "status": "COMPLETED", "type": "DEPOSIT"},
    )

    # =========================================================================
    # 10. Check Updated Balance – Alice (should now be $500.00)
    # =========================================================================
    pcap.ts += 0.3
    print("[*] Generating Session 10: GET Updated Balance (Alice = $500) …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52010,
        dport=GATEWAY_PORT,
        method="GET",
        path=f"/api/v1/wallets/{WALLET_A_ID}/balance",
        req_headers={"Authorization": f"Bearer {TOKEN_A}", "X-Request-Id": "req-010"},
        req_body=None,
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-010"},
        resp_body={"walletId": WALLET_A_ID, "balance": "500.00"},
    )

    # =========================================================================
    # 11. P2P Transfer – Alice sends $150.00 to Bob (Payment Service)
    # =========================================================================
    pcap.ts += 0.6
    print("[*] Generating Session 11: P2P Transfer Alice -> Bob ($150) …")
    transfer_tx_id = str(uuid.UUID("abcdefab-0000-0000-0000-000000000007"))
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52011,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/transfers",
        req_headers={
            "Authorization": f"Bearer {TOKEN_A}",
            "X-Request-Id": "req-011",
            "Idempotency-Key": "transfer-alice-bob-001",
        },
        req_body={
            "senderWalletId": WALLET_A_ID,
            "receiverWalletId": WALLET_B_ID,
            "amount": "150.00",
            "transactionId": transfer_tx_id,
        },
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-011"},
        resp_body={"transactionId": transfer_tx_id, "status": "COMPLETED"},
    )

    # =========================================================================
    # 12. Idempotent Duplicate Transfer (same Idempotency-Key, same response)
    # =========================================================================
    pcap.ts += 0.2
    print("[*] Generating Session 12: Duplicate Transfer (idempotency check) …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52012,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/transfers",
        req_headers={
            "Authorization": f"Bearer {TOKEN_A}",
            "X-Request-Id": "req-012",
            "Idempotency-Key": "transfer-alice-bob-001",  # same key
        },
        req_body={
            "senderWalletId": WALLET_A_ID,
            "receiverWalletId": WALLET_B_ID,
            "amount": "150.00",
            "transactionId": transfer_tx_id,
        },
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-012", "X-Idempotent-Replay": "true"},
        resp_body={"transactionId": transfer_tx_id, "status": "COMPLETED"},
    )

    # =========================================================================
    # 13. Check Balance After Transfer – Alice (should be $350.00)
    # =========================================================================
    pcap.ts += 0.3
    print("[*] Generating Session 13: GET Balance Alice (post-transfer = $350) …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52013,
        dport=GATEWAY_PORT,
        method="GET",
        path=f"/api/v1/wallets/{WALLET_A_ID}/balance",
        req_headers={"Authorization": f"Bearer {TOKEN_A}", "X-Request-Id": "req-013"},
        req_body=None,
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-013"},
        resp_body={"walletId": WALLET_A_ID, "balance": "350.00"},
    )

    # =========================================================================
    # 14. Check Balance After Transfer – Bob (should be $350.00)
    # =========================================================================
    pcap.ts += 0.2
    print("[*] Generating Session 14: GET Balance Bob (post-transfer = $350) …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52014,
        dport=GATEWAY_PORT,
        method="GET",
        path=f"/api/v1/wallets/{WALLET_B_ID}/balance",
        req_headers={"Authorization": f"Bearer {TOKEN_B}", "X-Request-Id": "req-014"},
        req_body=None,
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-014"},
        resp_body={"walletId": WALLET_B_ID, "balance": "350.00"},
    )

    # =========================================================================
    # 15. Insufficient Funds Transfer – Bob tries to send $500 (only has $350)
    # =========================================================================
    pcap.ts += 0.5
    print("[*] Generating Session 15: Insufficient Funds Transfer (400) …")
    bad_tx_id = str(uuid.UUID("badba000-0000-0000-0000-000000000099"))
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52015,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/transfers",
        req_headers={
            "Authorization": f"Bearer {TOKEN_B}",
            "X-Request-Id": "req-015",
            "Idempotency-Key": "transfer-bob-overspend-001",
        },
        req_body={
            "senderWalletId": WALLET_B_ID,
            "receiverWalletId": WALLET_A_ID,
            "amount": "500.00",
            "transactionId": bad_tx_id,
        },
        resp_status="400 Bad Request",
        resp_headers={"X-Trace-Id": "trace-015"},
        resp_body="Insufficient funds in sender wallet",
    )

    # =========================================================================
    # 16. Withdrawal – Alice withdraws $100.00 (Funding Service)
    # =========================================================================
    pcap.ts += 0.6
    print("[*] Generating Session 16: Withdrawal $100 from Alice's Wallet …")
    withdraw_tx_id = str(uuid.UUID("cafe0000-0000-0000-0000-000000000008"))
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52016,
        dport=GATEWAY_PORT,
        method="POST",
        path="/api/v1/funding/withdrawals",
        req_headers={
            "Authorization": f"Bearer {TOKEN_A}",
            "X-Request-Id": "req-016",
            "Idempotency-Key": "withdrawal-alice-001",
        },
        req_body={
            "walletId": WALLET_A_ID,
            "amount": "100.00",
            "reference": "WITHDRAWAL-TO-BANK-ACC-4421",
        },
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-016"},
        resp_body={
            "transactionId": withdraw_tx_id,
            "status": "COMPLETED",
            "type": "WITHDRAWAL",
        },
    )

    # =========================================================================
    # 17. List All Users (User Service)
    # =========================================================================
    pcap.ts += 0.3
    print("[*] Generating Session 17: List All Users …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52017,
        dport=GATEWAY_PORT,
        method="GET",
        path="/api/v1/users",
        req_headers={"Authorization": f"Bearer {TOKEN_A}", "X-Request-Id": "req-017"},
        req_body=None,
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-017"},
        resp_body=[
            {
                "userId": USER_A_ID,
                "fullName": "Alice Johnson",
                "phoneNumber": "+1-555-100-0001",
                "email": "alice@ppps.io",
            },
            {
                "userId": USER_B_ID,
                "fullName": "Bob Smith",
                "phoneNumber": "+1-555-200-0002",
                "email": "bob@ppps.io",
            },
        ],
    )

    # =========================================================================
    # 18. Get Single User Profile (User Service)
    # =========================================================================
    pcap.ts += 0.2
    print("[*] Generating Session 18: GET Single User Profile (Bob) …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52018,
        dport=GATEWAY_PORT,
        method="GET",
        path=f"/api/v1/users/{USER_B_ID}",
        req_headers={"Authorization": f"Bearer {TOKEN_A}", "X-Request-Id": "req-018"},
        req_body=None,
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-018"},
        resp_body={
            "userId": USER_B_ID,
            "fullName": "Bob Smith",
            "phoneNumber": "+1-555-200-0002",
            "email": "bob@ppps.io",
        },
    )

    # =========================================================================
    # 19. Final Balance Check – Alice (should be $250.00 after withdrawal)
    # =========================================================================
    pcap.ts += 0.3
    print("[*] Generating Session 19: Final Balance Alice ($250 after withdrawal) …")
    pcap.http_session(
        src_ip=CLIENT_IP,
        src_mac=CLIENT_MAC,
        dst_ip=GATEWAY_IP,
        dst_mac=SERVER_MAC,
        sport=52019,
        dport=GATEWAY_PORT,
        method="GET",
        path=f"/api/v1/wallets/{WALLET_A_ID}/balance",
        req_headers={"Authorization": f"Bearer {TOKEN_A}", "X-Request-Id": "req-019"},
        req_body=None,
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-019"},
        resp_body={"walletId": WALLET_A_ID, "balance": "250.00"},
    )

    # =========================================================================
    # 20. Internal service-to-service: Gateway -> Wallet Service (direct)
    #     Shows microservice mesh communication
    # =========================================================================
    pcap.ts += 0.1
    print("[*] Generating Session 20: Internal Gateway -> Wallet Service credit …")
    pcap.http_session(
        src_ip=GATEWAY_IP,
        src_mac=SERVER_MAC,
        dst_ip=WALLET_IP,
        dst_mac=bytes.fromhex("001122334413"),
        sport=43001,
        dport=WALLET_PORT,
        method="POST",
        path=f"/internal/wallets/{WALLET_B_ID}/credit",
        req_headers={
            "X-Internal-Service": "payment-service",
            "X-Correlation-Id": transfer_tx_id,
        },
        req_body={"amount": "150.00"},
        resp_status="200 OK",
        resp_headers={"X-Trace-Id": "trace-020"},
        resp_body={"walletId": WALLET_B_ID, "balance": "350.00"},
    )

    # =========================================================================
    # Write PCAP
    # =========================================================================
    pcap.write(output_path)
    print(f"\n[DONE] PCAP Summary:")
    print(f"  Output         : {output_path}")
    print(f"  Total packets  : {len(pcap.records)}")
    print(f"  Sessions       : 20 (2 DNS + 18 HTTP/TCP)")
    print(f"  Protocols      : DNS/UDP, HTTP/TCP (Ethernet II + IPv4)")
    print(
        f"  IP range       : {CLIENT_IP} <-> {GATEWAY_IP} (and internal microservices)"
    )
    print(f"  Endpoints hit  :")
    print(f"    POST  /api/v1/auth/register          (x2)")
    print(f"    POST  /api/v1/auth/login             (x2, one 400)")
    print(f"    POST  /api/v1/users                  (x2)")
    print(f"    GET   /api/v1/users                  (x1)")
    print(f"    GET   /api/v1/users/{{id}}              (x1)")
    print(f"    GET   /api/v1/wallets/{{id}}/balance   (x4)")
    print(f"    POST  /api/v1/funding/deposits       (x2)")
    print(f"    POST  /api/v1/funding/withdrawals    (x1)")
    print(f"    POST  /api/v1/transfers              (x3, one idempotent, one 400)")
    print(f"    POST  /internal/wallets/{{id}}/credit  (x1, internal)")


if __name__ == "__main__":
    import sys

    out = sys.argv[1] if len(sys.argv) > 1 else "capture.pcap"
    build_pcap(out)
