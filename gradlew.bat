@echo off
setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%
set DEFAULT_JVM_OPTS=
set GRADLE_OPTS=-Dfile.encoding=UTF-8
if not defined GRADLE_USER_HOME set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
if not exist "%CLASSPATH%" (
    echo ERROR: gradle-wrapper.jar not found at %CLASSPATH%
    echo Open project in Android Studio first to download Gradle wrapper.
    exit /b 1
)
if "%JAVA_HOME%"=="" (
    echo ERROR: JAVA_HOME not set. Please install JDK 17 and set JAVA_HOME.
    echo Example: set JAVA_HOME=C:\Program Files\Java\jdk-17
    echo Then run: %~nx0 assembleDebug
    exit /b 1
)
"%JAVA_HOME%\bin\java.exe" %DEFAULT_JVM_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%