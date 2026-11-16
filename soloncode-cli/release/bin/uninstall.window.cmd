@echo off
rem Solon Code CLI Uninstaller (CMD wrapper - bypasses PowerShell execution policy)
rem Usage: Double-click or run "uninstall.cmd" in CMD / PowerShell / Windows Terminal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1"