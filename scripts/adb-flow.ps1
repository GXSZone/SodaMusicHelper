# 闭环验证脚本：逐状态截图 + SodaAuto 实时日志
# 用法：
#   .\scripts\adb-flow.ps1                       # 用 debug 广播启动（推荐，无需点坐标）
#   .\scripts\adb-flow.ps1 -Seconds 180 -IntervalSec 3
#   .\scripts\adb-flow.ps1 -TapX 628 -TapY 2231  # 也可改为点按钮启动
# 说明：
#   - 需要 debug 包（内含 DebugCommandReceiver，release 不包含）
#   - 运行中不要用 uiautomator dump：会注册 UiAutomation，把无障碍服务解绑，引擎会被停
param(
    [int]$Seconds = 180,
    [int]$IntervalSec = 3,
    [int]$TapX = 0,
    [int]$TapY = 0,
    [switch]$NoAutoStart,
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$adb = "D:\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb 不存在: $adb" }

if (-not $OutDir) { $OutDir = Join-Path $env:TEMP ("soda-flow-" + (Get-Date -Format "MMdd-HHmmss")) }
New-Item -ItemType Directory -Force $OutDir | Out-Null
$logPath = Join-Path $OutDir "sodaauto.log"

& $adb devices | Out-String | Write-Host
# 不要 force-stop：会把已绑定的无障碍服务解绑，导致 start 报"服务未连接"

# 后台实时抓 SodaAuto 日志（关键：末次 logcat -d 会被系统日志挤掉）
$job = Start-Job -ScriptBlock { param($a, $l) & $a logcat -v time -s SodaAuto *> $l } -ArgumentList $adb, $logPath
Start-Sleep -Seconds 1

& $adb shell am start -n com.sodamusic.helper/.MainActivity | Out-Null
Start-Sleep -Seconds 3

# 等无障碍服务真正重连（force-stop 后系统需要几秒重新绑定），否则 start 会报"服务未连接"
for ($w = 1; $w -le 15; $w++) {
    Start-Sleep -Seconds 1
    if ((Get-Content $logPath -Encoding UTF8 -Raw -ErrorAction SilentlyContinue) -match '无障碍服务已连接') { Write-Host "无障碍服务已连接（等待 $w s）"; break }
}

if (-not $NoAutoStart) {
    if ($TapX -gt 0 -and $TapY -gt 0) {
        Write-Host "点击开始按钮 ($TapX, $TapY)"
        & $adb shell input tap $TapX $TapY | Out-Null
    } else {
        Write-Host "发送 DEBUG_START 广播（显式组件，避免 Android 8+ 隐式广播限制）"
        & $adb shell am broadcast -n com.sodamusic.helper/.DebugCommandReceiver -a com.sodamusic.helper.DEBUG_START | Out-Null
    }
    Start-Sleep -Seconds 3
}

$frames = @()
$deadline = (Get-Date).AddSeconds($Seconds)
$i = 0
while ((Get-Date) -lt $deadline) {
    $i++
    $name = "f{0:d3}.png" -f $i
    & $adb shell screencap -p /sdcard/_flow.png | Out-Null
    & $adb pull /sdcard/_flow.png (Join-Path $OutDir $name) | Out-Null
    $frames += [pscustomobject]@{ Index = $i; Time = (Get-Date -Format "HH:mm:ss"); File = $name }
    Start-Sleep -Seconds $IntervalSec
}

& $adb shell am broadcast -n com.sodamusic.helper/.DebugCommandReceiver -a com.sodamusic.helper.DEBUG_STOP | Out-Null
Start-Sleep -Milliseconds 500
Stop-Job $job; Receive-Job $job | Out-Null; Remove-Job $job -Force

$focus = & $adb shell dumpsys window 2>&1 | Select-String 'mCurrentFocus' | Out-String
$focus | Out-File (Join-Path $OutDir "focus.txt") -Encoding utf8

Write-Host ""
Write-Host "=== 状态序列 ==="
Get-Content $logPath -Encoding UTF8 |
    Where-Object { $_ -notmatch 'adbd' } |
    ForEach-Object { ($_ -replace '^.*SodaAuto\s*:\s*','') } |
    Out-String -Width 200 | Write-Host

Write-Host "=== 截图时间线 ==="
$frames | Format-Table -AutoSize | Out-String | Write-Host
Write-Host "=== 最终前台 ==="
$focus | Write-Host
Write-Host "输出目录: $OutDir"