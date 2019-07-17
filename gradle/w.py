#!/usr/bin/env python

# the gradle wrapper shell and batch scripts are kinda messy
#  - weird symlink chasing in APP_HOME (`gradle wrapper` doesn't make symlinks)
#  - JAVA_HOME envar used to find exe (easier to just set PATH correctly)
#  - mac os `-Xdock:icon` points nowhere
#  - why cygwin? is the .bat script not sufficient on windows?
#  - what is that insane sed escaping and overwriting $@ about?
#  - what is wrong with org.gradle.appname default value?
# this file keeps and simplifies the important bits

import sys, os

# =============================================================================

try :
    import resource
    ( soft, hard ) = resource.getrlimit( resource.RLIMIT_NOFILE )
    if soft != hard :
        # only superuser can change hard limit, so that's the best we can set
        resource.setrlimit( resource.RLIMIT_NOFILE, ( hard, hard ) )
except :
    # ImportError: module does not exist on systems that don't support it
    # ValueError on MacOS: hard == 2^32-1, but that is not accepted as soft
    #   (note that gradlew bash script doesn't try to ulimit on darwin)
    pass # could not change limit, but might succeed with current limit

# =============================================================================

thisDir = sys.path[0]
rootDir = os.path.dirname( thisDir )

fromDir = os.getcwd()
# might be outside our project tree in certain conditions, including:
#  - double-clicking this script from inside a graphical file manager
#  - typing into a shell the long path to the script
# it seems like the right thing to do in all these cases is always...
if fromDir.startswith( thisDir ) or not fromDir.startswith( rootDir ) :
    os.chdir( rootDir )

# =============================================================================

os.execvp( 'java', [
    'java',
    '-classpath', os.path.join( thisDir, 'wrapper', 'gradle-wrapper.jar' ),
    'org.gradle.wrapper.GradleWrapperMain'
] + sys.argv[ 1 : ] )

