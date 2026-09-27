#!/bin/sh
# Opens the Document Workbench LAN sync ports on Linux (Mint/Ubuntu: ufw, Fedora/RHEL: firewalld), for the
# local subnet only:
#   UDP 47777  peer discovery (multicast 239.255.77.77, subnet broadcast, unicast seeds)
#   TCP 47778  SHA-256 verified file transfer
#
# Usage:  sudo sh open-lan-ports.sh [subnet]      e.g.  sudo sh open-lan-ports.sh 192.168.1.0/24
# Without an argument the subnet of the interface holding the default route is used.
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo "Run as root: sudo sh $0 [subnet]" >&2
    exit 1
fi

SUBNET="${1:-}"
if [ -z "$SUBNET" ]; then
    DEV=$(ip -4 route show default | awk '{for (i = 1; i < NF; i++) if ($i == "dev") {print $(i + 1); exit}}')
    CIDR=$(ip -4 -o addr show dev "$DEV" | awk '{print $4; exit}')
    # 192.168.1.8/24 -> 192.168.1.0/24 (network address)
    SUBNET=$(python3 -c "import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)" "$CIDR" 2>/dev/null \
        || echo "$CIDR")
fi
echo "Local subnet: $SUBNET"

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
    ufw allow from "$SUBNET" to any port 47777 proto udp comment 'Document Workbench LAN discovery'
    ufw allow from "$SUBNET" to any port 47778 proto tcp comment 'Document Workbench LAN transfer'
    echo "ufw rules added."
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    firewall-cmd --permanent --add-rich-rule="rule family=ipv4 source address=$SUBNET port port=47777 protocol=udp accept"
    firewall-cmd --permanent --add-rich-rule="rule family=ipv4 source address=$SUBNET port port=47778 protocol=tcp accept"
    firewall-cmd --reload
    echo "firewalld rules added."
else
    echo "No active ufw/firewalld: nothing to open (ports are already reachable)."
fi
echo "Done. Restart Document Workbench, then check the peer list with:  > lan"
