@echo off
title Light AI Standalone Server
echo Starting Light AI Standalone Server (Port 8080)...
java -jar "%~dp0light-ai-server\target\light-ai-server-0.1.0-SNAPSHOT.jar"
pause
