@echo off
rem Solon Code CLI Installer (CMD wrapper - bypasses PowerShell execution policy)
rem Usage: Double-click or run "install.cmd" in CMD / PowerShell / Windows Terminal

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"