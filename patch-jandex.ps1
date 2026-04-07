param(
    [Parameter(Mandatory)][string]$ThorntailJar,
    [Parameter(Mandatory)][string]$JandexPatchJar
)

# Patch Jandex 2.1.2 inside the Thorntail fat JAR with 2.4.4
# The fat JAR contains m2repo/org/jboss/jandex/2.1.2.Final/jandex-2.1.2.Final.jar
# We replace it with the 2.4.4 jar (already renamed to jandex-2.1.2.Final.jar by Maven)

$entryPath = "m2repo/org/jboss/jandex/2.1.2.Final/jandex-2.1.2.Final.jar"

if (-not (Test-Path $ThorntailJar)) {
    Write-Error "Thorntail JAR not found: $ThorntailJar"
    exit 1
}
if (-not (Test-Path $JandexPatchJar)) {
    Write-Error "Jandex patch JAR not found: $JandexPatchJar"
    exit 1
}

try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $jarFullPath = (Resolve-Path $ThorntailJar).Path
    $patchBytes = [System.IO.File]::ReadAllBytes((Resolve-Path $JandexPatchJar).Path)

    $zip = [System.IO.Compression.ZipFile]::Open($jarFullPath, 'Update')

    $existingEntry = $zip.Entries | Where-Object { $_.FullName -eq $entryPath } | Select-Object -First 1

    if ($null -eq $existingEntry) {
        Write-Error "Entry '$entryPath' not found in JAR"
        $zip.Dispose()
        exit 1
    }

    # Delete old entry and create new one with the patched jar
    $existingEntry.Delete()
    $newEntry = $zip.CreateEntry($entryPath, [System.IO.Compression.CompressionLevel]::Optimal)
    $stream = $newEntry.Open()
    $stream.Write($patchBytes, 0, $patchBytes.Length)
    $stream.Close()

    $zip.Dispose()
    Write-Host "Jandex patched successfully ($entryPath)"
    exit 0
} catch {
    Write-Error "Failed to patch Jandex: $_"
    exit 1
}
