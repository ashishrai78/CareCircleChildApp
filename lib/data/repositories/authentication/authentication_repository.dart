import 'package:background/features/childsetup/screens/permission_setup_screen.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_background_service/flutter_background_service.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';
import 'package:google_sign_in/google_sign_in.dart';
import '../../../features/authentication/screens/login/login_screen.dart';
import '../../../features/authentication/screens/onBoarding/onBoarding_screen.dart';
import '../../../features/authentication/screens/signUp/verifyEmail_screen.dart';
import '../../../utils/exceptions/firebase_auth_exceptions.dart';
import '../../../utils/exceptions/firebase_exceptons.dart';
import '../../../utils/exceptions/format_exceptions.dart';
import '../../../utils/exceptions/platform_exceptions.dart';
import '../user/user_repository.dart';

class AuthenticationRepository extends GetxController {
  static AuthenticationRepository get instance => Get.find();

  final storage = GetStorage();
  final _auth = FirebaseAuth.instance;
  User? get currentUser => _auth.currentUser;

  // 🔥 Track if we've already redirected (prevents multiple redirects)
  bool _hasRedirected = false;

  @override
  void onReady() {
    super.onReady();
    _initAuthListener();
  }

  /// 🔥 FIX: Listen to authStateChanges() — handles Firebase auth state restoration delay
  void _initAuthListener() {
    // Listen for auth state changes (fires on app launch too, after Firebase restores session)
    _auth.authStateChanges().listen((User? user) {
      if (_hasRedirected) return;

      if (user != null) {
        // 🔥 Wait a bit to ensure Firebase has fully restored state
        Future.delayed(const Duration(milliseconds: 500), () {
          screenRedirect();
        });
      } else {
        // No user — check first time
        _checkFirstTime();
        _hasRedirected = true;
      }
    }, onError: (error) {
      debugPrint("🔥 Auth state error: $error");
      _checkFirstTime();
      _hasRedirected = true;
    });
  }

  /// Check if first time user → show onboarding, else login
  void _checkFirstTime() {
    storage.writeIfNull('isFirstTime', true);
    if (storage.read('isFirstTime') == true) {
      Get.offAll(() => OnboardingScreen());
    } else {
      Get.offAll(() => LoginScreen());
    }
  }

  /// 🔥 FIX: Reset redirect flag on logout so next login works
  void _resetRedirectFlag() {
    _hasRedirected = false;
  }

  // function to redirect to right Screen
  void screenRedirect() async {
    final user = currentUser;
    if (user == null) {
      _checkFirstTime();
      _hasRedirected = true;
      return;
    }

    // 🔥 Write UID to storage + pass to native
    await storage.write('currentUserId', user.uid);
    try {
      final service = FlutterBackgroundService();
      service.invoke('setUserId', {'uid': user.uid,},);
      const platform = MethodChannel('watchdog_channel');
      await platform.invokeMethod('setUserId', {'uid': user.uid});
      debugPrint("✅ UID passed to native: ${user.uid}");
    } catch (e) {
      debugPrint("Failed to pass UID to native: $e");
    }

    if (user.emailVerified) {
      // Verified → go to PermissionSetupScreen
      try {
        await UserRepository.instance.generateChildCode(user.uid);
      } catch (e) {
        debugPrint("generateChildCode failed: $e");
      }
      Get.offAll(() => PermissionSetupScreen());
    } else {
      // Not verified → verify email screen
      Get.offAll(() => VerifyEmailScreen(userEmail: user.email ?? ''));
    }
    _hasRedirected = true;
  }

  // UserRegister with Email/ Password
  Future<UserCredential> registerUser(String email, password) async {
    try {
      UserCredential userCredential = await _auth
          .createUserWithEmailAndPassword(email: email, password: password);
      return userCredential;
    } on FirebaseAuthException catch (e) {
      throw UFirebaseAuthException(e.code).message;
    } on FirebaseException catch (e) {
      throw UFirebaseException(e.code).message;
    } on UPlatformException catch (e) {
      throw UPlatformException(e.code).message;
    } on FormatException {
      throw UFormatException();
    } catch (e) {
      throw 'Something went wrong! Please try again';
    }
  }

  // Login with Email/ Password
  Future<UserCredential> loginWithEmailPassword(String email, password) async {
    try {
      UserCredential userCredential = await _auth.signInWithEmailAndPassword(
        email: email,
        password: password,
      );
      // 🔥 FIX: Reset redirect flag after login
      _resetRedirectFlag();
      return userCredential;
    } on FirebaseAuthException catch (e) {
      throw UFirebaseAuthException(e.code).message;
    } on FirebaseException catch (e) {
      throw UFirebaseException(e.code).message;
    } on UPlatformException catch (e) {
      throw UPlatformException(e.code).message;
    } on FormatException {
      throw UFormatException();
    } catch (e) {
      throw 'Something went wrong! Please try again';
    }
  }

  // Authentication with Google Account
  Future<UserCredential> signInWithGoogle() async {
    try {
      final GoogleSignInAccount? googleAccount = await GoogleSignIn().signIn();
      final GoogleSignInAuthentication? googleAuth =
      await googleAccount?.authentication;

      final OAuthCredential credential = GoogleAuthProvider.credential(
        idToken: googleAuth?.idToken,
        accessToken: googleAuth?.accessToken,
      );

      UserCredential userCredential = await _auth.signInWithCredential(
        credential,
      );
      // 🔥 FIX: Reset redirect flag after Google login
      _resetRedirectFlag();
      return userCredential;
    } on FirebaseAuthException catch (e) {
      throw UFirebaseAuthException(e.code).message;
    } on FirebaseException catch (e) {
      throw UFirebaseException(e.code).message;
    } on UPlatformException catch (e) {
      throw UPlatformException(e.code).message;
    } on FormatException {
      throw UFormatException();
    } catch (e) {
      throw 'Something went wrong! Please try again';
    }
  }

  // Send Email Verification
  Future<void> sendEmailVerification() async {
    try {
      await _auth.currentUser?.sendEmailVerification();
    } on FirebaseAuthException catch (e) {
      throw UFirebaseAuthException(e.code).message;
    } on FirebaseException catch (e) {
      throw UFirebaseException(e.code).message;
    } on UPlatformException catch (e) {
      throw UPlatformException(e.code).message;
    } on FormatException {
      throw UFormatException();
    } catch (e) {
      throw 'Something went wrong! Please try again';
    }
  }

  // Send Email for forget password
  Future<void> sendEmailForForgetPassword(String email) async {
    try {
      await _auth.sendPasswordResetEmail(email: email);
    } on FirebaseAuthException catch (e) {
      throw UFirebaseAuthException(e.code).message;
    } on FirebaseException catch (e) {
      throw UFirebaseException(e.code).message;
    } on UPlatformException catch (e) {
      throw UPlatformException(e.code).message;
    } on FormatException {
      throw UFormatException();
    } catch (e) {
      throw 'Something went wrong! Please try again';
    }
  }

  // Logout User Account
  Future<void> logOut() async {
    try {
      await _auth.signOut();
      await GoogleSignIn().signOut();
      // 🔥 FIX: Clear stored UID + reset redirect flag
      await storage.remove('currentUserId');
      await storage.remove('lastNotifiedUidToNative');
      _resetRedirectFlag();
      Get.offAll(() => LoginScreen());
    } on FirebaseAuthException catch (e) {
      throw UFirebaseAuthException(e.code).message;
    } on FirebaseException catch (e) {
      throw UFirebaseException(e.code).message;
    } on UPlatformException catch (e) {
      throw UPlatformException(e.code).message;
    } on FormatException {
      throw UFormatException();
    } catch (e) {
      throw 'Something went wrong! Please try again';
    }
  }

  // reauthenticateWithCredential by email/password
  Future<void> reAuthenticateWithCredential(String email, password) async {
    try {
      AuthCredential authCredential = EmailAuthProvider.credential(
        email: email,
        password: password,
      );
      await currentUser!.reauthenticateWithCredential(authCredential);
    } on FirebaseAuthException catch (e) {
      throw UFirebaseAuthException(e.code).message;
    } on FirebaseException catch (e) {
      throw UFirebaseException(e.code).message;
    } on UPlatformException catch (e) {
      throw UPlatformException(e.code).message;
    } on FormatException {
      throw UFormatException();
    } catch (e) {
      throw 'Something went wrong! Please try again';
    }
  }
}

  // Delete User Account
  /* Future<void> deleteUserAccount() async{
    try{
      await UserRepository.instance.removeUserRecord(currentUser!.uid);
      await _auth.currentUser?.delete();
      // Remove User profile picture from Cloudinary
      String publicId = UserController.instance.user.value.publicId;
      if(publicId.isNotEmpty){
        UserRepository.instance.deleteProfilePicture(publicId);
      }

    } on FirebaseAuthException catch(e){
      throw UFirebaseAuthException(e.code).message;
    } on FirebaseException catch(e){
      throw UFirebaseException(e.code).message;
    } on UPlatformException catch(e){
      throw UPlatformException(e.code).message;
    } on FormatException {
      throw UFormatException();
    }catch(e) {
      throw 'Something went wrong! Please try again';
    }
  }*/

