@rem
@rem Gradle start up script for Windows.
@rem
@rem It finds a Java runtime (JAVA_HOME or "java" from the PATH) and runs the
@rem gradle-wrapper.jar that sits next to this file.
@rem
@rem Usage:
@rem   gradlew.bat assembleRelease
@rem
@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" goto missingJar

@rem --- find java -------------------------------------------------------------
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto execute
goto missingJava

:findJavaFromJavaHome
set JAVA_EXE=%JAVA_HOME%\bin\java.exe
if exist "%JAVA_EXE%" goto execute
goto missingJava

:missingJar
echo ERROR: %WRAPPER_JAR% is missing. 1>&2
goto fail

:missingJava
echo ERROR: no Java runtime found. Install JDK 17 and set JAVA_HOME. 1>&2
goto fail

:execute
"%JAVA_EXE%" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
if "%ERRORLEVEL%"=="0" goto end

:fail
exit /b 1

:end
endlocal
