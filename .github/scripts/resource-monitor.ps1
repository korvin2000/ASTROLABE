# Windows CI diagnostic (2026-09-28): the hosted runner lost communication while the suite ran, after process
# spawns failed with 0xC0000142. One line per minute of commit, memory, process/handle counts and the largest
# processes lands in the live step log, which survives a runner loss. Remove once the cause is known.
# The step stops it by killing it or, as a fallback, by creating .resource-monitor.stop.
while (-not (Test-Path '.resource-monitor.stop')) {
    try {
        $os = Get-CimInstance Win32_OperatingSystem
        $p = Get-Process
        $top = ($p | Sort-Object WorkingSet64 -Descending | Select-Object -First 6 |
            ForEach-Object { '{0}#{1} {2:N0}MB' -f $_.ProcessName, $_.Id, ($_.WorkingSet64 / 1MB) }) -join ', '
        $counts = ($p | Group-Object ProcessName | Sort-Object Count -Descending | Select-Object -First 5 |
            ForEach-Object { '{0}x{1}' -f $_.Name, $_.Count }) -join ' '
        Write-Output ('[resources {0:HH:mm:ss}] commit {1:N1}/{2:N1} GB, free RAM {3:N1} GB, processes {4} ({5}), handles {6}; top: {7}' -f
            (Get-Date), (($os.TotalVirtualMemorySize - $os.FreeVirtualMemory) / 1MB), ($os.TotalVirtualMemorySize / 1MB),
            ($os.FreePhysicalMemory / 1MB), $p.Count, $counts, ($p | Measure-Object HandleCount -Sum).Sum, $top)
    } catch {
        Write-Output "[resources] sample failed: $($_.Exception.Message)"
    }
    for ($i = 0; $i -lt 60 -and -not (Test-Path '.resource-monitor.stop'); $i++) { Start-Sleep -Seconds 1 }
}
