param(
    [Parameter(Mandatory = $true)][string] $PluginJar,
    [Parameter(Mandatory = $true)][string] $OutputZip,
    [string] $GradleCache = $env:GRADLE_USER_HOME
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $GradleCache) { throw 'Pass -GradleCache or set GRADLE_USER_HOME.' }
$pluginPath = (Resolve-Path -LiteralPath $PluginJar).Path
$cacheRoot = Join-Path $GradleCache 'caches\modules-2\files-2.1'
$work = Join-Path ([System.IO.Path]::GetTempPath()) ('trchat-offline-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null

function Read-ZipText([string] $archivePath, [string] $entryName) {
    $archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        $entry = $archive.GetEntry($entryName)
        if ($null -eq $entry) { throw "Missing $entryName in $archivePath" }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
}

$envProperties = Read-ZipText $pluginPath 'META-INF/taboolib/env.properties'
$versionProperties = Read-ZipText $pluginPath 'META-INF/taboolib/version.properties'
$tabooVersion = ([regex]::Match($versionProperties, '(?m)^taboolib=(.+)$')).Groups[1].Value.Trim()
$kotlinVersion = ([regex]::Match($versionProperties, '(?m)^kotlin=(.+)$')).Groups[1].Value.Trim()
$moduleLine = ([regex]::Match($envProperties, '(?m)^module=(.+)$')).Groups[1].Value.Trim()
if (-not $tabooVersion -or -not $kotlinVersion -or -not $moduleLine) {
    throw 'The plugin is missing TabooLib runtime dependency metadata.'
}

$script:artifactCount = 0
$script:totalBytes = 0
function Add-Artifact([string] $group, [string] $artifact, [string] $version,
                      [string] $repository, [bool] $includePom = $false) {
    $relative = ($group.Replace('.', '/') + "/$artifact/$version")
    $sourceDir = Join-Path $cacheRoot ($group + "\$artifact\$version")
    $targetDir = Join-Path $work ('libraries/' + $relative)
    New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
    $baseName = "$artifact-$version"
    foreach ($extension in @('jar') + $(if ($includePom) { @('pom') } else { @() })) {
        $name = "$baseName.$extension"
        $cached = @(Get-ChildItem -LiteralPath $sourceDir -Recurse -File -Filter $name -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -eq $name } | Select-Object -First 1)
        $target = Join-Path $targetDir $name
        if ($cached.Count -gt 0) {
            Copy-Item -LiteralPath $cached[0].FullName -Destination $target
        } else {
            $url = $repository.TrimEnd('/') + "/$relative/$name"
            Write-Host "Downloading $url"
            & curl.exe --fail --location --retry 3 --connect-timeout 10 --max-time 90 --silent --show-error --output $target $url
            if ($LASTEXITCODE -ne 0) { throw "Could not download $url" }
        }
        if ($extension -eq 'jar') {
            $check = [System.IO.Compression.ZipFile]::OpenRead($target)
            $check.Dispose()
        }
        $sha1 = (Get-FileHash -LiteralPath $target -Algorithm SHA1).Hash.ToLowerInvariant()
        [System.IO.File]::WriteAllText("$target.sha1", $sha1, [System.Text.Encoding]::ASCII)
        $script:totalBytes += (Get-Item -LiteralPath $target).Length
    }
    $script:artifactCount++
}

try {
    $tabooRepo = 'https://repo.tabooproject.org/repository/releases'
    $chinaCentral = 'https://maven.aliyun.com/repository/central'
    $modules = @('common-env', 'common-util', 'common-legacy-api', 'common-platform-api') +
        @($moduleLine.Split(',') | ForEach-Object { $_.Trim() })
    foreach ($module in ($modules | Sort-Object -Unique)) {
        Add-Artifact 'io.izzel.taboolib' $module $tabooVersion $tabooRepo
    }

    foreach ($artifact in @('kotlin-stdlib', 'kotlin-stdlib-jdk7', 'kotlin-stdlib-jdk8', 'kotlin-reflect')) {
        Add-Artifact 'org.jetbrains.kotlin' $artifact $kotlinVersion $chinaCentral $true
    }
    Add-Artifact 'me.lucko' 'jar-relocator' '1.7' $chinaCentral $true
    foreach ($artifact in @('asm', 'asm-util', 'asm-commons', 'asm-tree', 'asm-analysis')) {
        Add-Artifact 'org.ow2.asm' $artifact '9.8' $chinaCentral $true
    }
    foreach ($artifact in @('reflex', 'analyser')) {
        Add-Artifact 'org.tabooproject.reflex' $artifact '1.2.4' $tabooRepo $true
    }

    $readme = @'
TrChat 离线依赖包

适用于随包提供的 TrChat 插件版本。操作步骤：
1. 停止 Minecraft 服务端。
2. 将此 ZIP 内的 libraries 文件夹解压并合并到服务端工作目录（与 shiroha-26.3.jar 同级）。
   示例：/Data/Data-3生存服/libraries/io/izzel/taboolib/common-env/...
3. 使用对应版本的 TrChat 插件，重新启动服务端。

每个 JAR 附有 .sha1 校验文件，TabooLib 会验证本地文件并跳过重复下载。
本包仅包含 TrChat 启动所需的 TabooLib/Kotlin 等依赖；其他插件的依赖互不影响。
'@
    [System.IO.File]::WriteAllText((Join-Path $work 'README-离线依赖.txt'), $readme,
        [System.Text.UTF8Encoding]::new($false))
    $outputPath = [System.IO.Path]::GetFullPath($OutputZip)
    New-Item -ItemType Directory -Path ([System.IO.Path]::GetDirectoryName($outputPath)) -Force | Out-Null
    if (Test-Path -LiteralPath $outputPath) { Remove-Item -LiteralPath $outputPath -Force }
    [System.IO.Compression.ZipFile]::CreateFromDirectory($work, $outputPath,
        [System.IO.Compression.CompressionLevel]::Optimal, $false)
    Write-Host "Wrote $outputPath ($script:artifactCount artifacts, $script:totalBytes bytes of payload)"
} finally {
    $resolvedWork = [System.IO.Path]::GetFullPath($work)
    $resolvedTemp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedWork.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $resolvedWork).StartsWith('trchat-offline-')) {
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force
    }
}
