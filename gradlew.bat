@echo off
setlocal
set APP_HOME=%~dp0
if "%JAVA_HOME%"=="" (
    set JAVACMD=java.exe
) else (
    set JAVACMD=%JAVA_HOME%\bin\java.exe
)
"%JAVACMD%" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
