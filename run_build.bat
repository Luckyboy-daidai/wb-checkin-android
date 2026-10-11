@echo off
rem ============================================================
rem  WB签到助手 构建脚本
rem  用 subst 把中文项目路径映射成 ASCII 盘符（X:=源码, Y:=构建环境），
rem  规避 Gradle/aapt2 对中文路径的编码风险。X/Y 若被占用请改字母。
rem  产物：安卓项目源码\app\build\outputs\apk\release\app-release.apk
rem ============================================================
subst X: /d >nul 2>&1
subst Y: /d >nul 2>&1
subst X: "F:\work\workbuddy手机自动签到\安卓项目源码"
subst Y: "F:\work\workbuddy手机自动签到\构建环境"
if not exist X:\app echo [警告] X: 映射失败，请检查盘符占用
cd /d X:\
set "JAVA_HOME=Y:\jdk\jdk-17.0.20.1+1"
set "ANDROID_HOME=Y:\sdk"
call Y:\gradle\gradle-8.7\bin\gradle.bat assembleRelease --no-daemon
echo BUILD_EXIT=%ERRORLEVEL%
