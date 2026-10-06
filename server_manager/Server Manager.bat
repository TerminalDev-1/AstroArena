@echo off
rem Opens the AstroArena Server Manager. Needs Python (the same one the server uses); nothing else.
cd /d "%~dp0"
where pythonw >nul 2>nul && (start "" pythonw manager.py) || python manager.py
