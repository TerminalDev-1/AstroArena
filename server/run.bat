@echo off
rem Starts the AstroArena server. Leave this window open while you play.
cd /d "%~dp0"
python run.py %*
pause
