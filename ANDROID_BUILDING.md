# Building Android Client

## Install Requirements

    $ sudo yum install git-core java gmp-devel gperf ant zlib.i686 vim
    $ yum group install "Development Tools"


## Clone the Aquto Repositories

    $ git clone https://github.com/aquto/strongswan.git
    $ git clone https://github.com/aquto/android-ndk-openssl.git -b ndk-static


## Get the Android SDK and NDK

**Note:** Links may change, check out [Android Developers](http://developer.android.com) site for latest versions.

    $ wget http://dl.google.com/android/android-sdk_r24.0.2-linux.tgz
    $ wget http://dl.google.com/android/ndk/android-ndk-r10d-linux-x86_64.bin
    $ tar xvfz android-sdk_r24.0.2-linux.tgz
    $ chmod u+x android-ndk-r10d-linux-x86_64.bin
    $ ./android-ndk-r10d-linux-x86_64.bin
    $ mv android-ndk-r10d android-ndk
    $ mv android-sdk-linux android-sdk
    $ ./android-sdk/tools/android update sdk --no-ui

**Note:** This might die in the middle of the update because adb isn't in the path. I updated my path anyways and added the path to ndk-build which we'll need later.

    $ export PATH=$HOME/android-sdk/platform-tools:$HOME/android-sdk/tools:$HOME/android-ndk:$PATH

If you get the error below you need to have the 32-bit version of libstdc++:

    $ adb
    -bash: /home/vagrant/android-sdk/platform-tools/adb: /lib/ld-linux.so.2: bad ELF interpreter: No such file or directory
    $ sudo yum update libstdc++
    $ sudo yum install libstdc++.i686

once all that is done, rerun:

    $ ./android-sdk/tools/android update sdk --no-ui


## Generate a Keystore for Release Signing

    $ cd ~/.android
    $ keytool -genkey -v -keystore release.keystore -alias release -keyalg RSA -keysize 2048 -validity 10000
    Enter keystore password:  
    Re-enter new password: 
    What is your first and last name?
      [Unknown]:  Greg DeAngelis
    What is the name of your organizational unit?
      [Unknown]:  
    What is the name of your organization?
      [Unknown]:  Aquto
    What is the name of your City or Locality?
      [Unknown]:  Boston
    What is the name of your State or Province?
      [Unknown]:  MA
    What is the two-letter country code for this unit?
      [Unknown]:  US
    Is CN=Greg DeAngelis, OU=Unknown, O=Aquto, L=Boston, ST=MA, C=US correct?
      [no]:  yes

    Generating 2,048 bit RSA key pair and self-signed certificate (SHA256withRSA) with a validity of 10,000 days
        for: CN=Greg DeAngelis, OU=Unknown, O=Aquto, L=Boston, ST=MA, C=US
    Enter key password for <release>
        (RETURN if same as keystore password):  
    [Storing release.keystore]


## Building StrongSwan Android Client

    $ cd strongswan/src/frontends/android/
    $ ln -s ../../../../ jni/strongswan
    $ ln -s ../../../../../android-ndk-openssl/ jni/openssl
    $ cd ../../..
    $ ./autogen.sh && ./configure && make && make distclean
    $ cd strongswan/src/frontends/android/
    $ ndk-build
    $ android update project -p . -n AQStrongSwan
    $ vim ant.properties

    key.store=/path/to/your/keystore
    key.alias=release
    key.store.password=yourkeystorepassword
    key.alias.password=yourkeyaliaspassword

    $ ant release

The apk file is located in: bin/AQStrongSwan-release.apk. To install it use:

    $ adb install bin/AQStrongSwan-release.apk
