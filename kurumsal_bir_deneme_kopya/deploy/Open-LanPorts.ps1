<#
.SYNOPSIS
    Opens the Document Workbench LAN sync ports in Windows Defender Firewall, for the local subnet only.

.DESCRIPTION
    LAN sync needs two inbound ports:
      UDP 47777  peer discovery (multicast 239.255.77.77, subnet broadcast, unicast seeds)
      TCP 47778  SHA-256 verified file transfer
    Windows blocks both by default, so peers can ping each other but never see each other in Document
    Workbench. The rules created here accept traffic from the local subnet only (RemoteAddress LocalSubnet):
    nothing becomes reachable from outside the LAN. Running the script again replaces the rules.

    Run once per Windows node from an elevated PowerShell. -Remove deletes the rules again.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\Open-LanPorts.ps1
#>
[CmdletBinding()]
param(
    [switch] $Remove
)

$ErrorActionPreference = 'Stop'

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Error 'Run this script as Administrator (firewall rules are machine-wide).'
    exit 1
}

$rules = @(
    @{ Name = 'Document Workbench LAN discovery (UDP 47777)'; Protocol = 'UDP'; Port = 47777 },
    @{ Name = 'Document Workbench LAN transfer (TCP 47778)'; Protocol = 'TCP'; Port = 47778 }
)

foreach ($r in $rules) {
    Get-NetFirewallRule -DisplayName $r.Name -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}
if ($Remove) {
    Write-Host 'Document Workbench LAN rules removed.'
    exit 0
}

foreach ($r in $rules) {
    # All profiles: home Wi-Fi is often classified as "Public"; LocalSubnet still keeps it LAN-only.
    New-NetFirewallRule -DisplayName $r.Name -Direction Inbound -Action Allow -Protocol $r.Protocol `
        -LocalPort $r.Port -RemoteAddress LocalSubnet -Profile Any | Out-Null
    Write-Host ("Allowed inbound {0} {1} from the local subnet" -f $r.Protocol, $r.Port)
}

$profiles = Get-NetConnectionProfile | ForEach-Object { "$($_.InterfaceAlias)=$($_.NetworkCategory)" }
Write-Host ("Network profiles: {0}" -f ($profiles -join ', '))
Write-Host 'Done. Restart Document Workbench, then check the peer list with:  > lan'
