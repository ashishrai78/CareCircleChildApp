
import 'package:background/features/childsetup/screens/permission_screen.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';
import 'package:google_sign_in/google_sign_in.dart';
import '../../../features/authentication/screens/login/login_screen.dart';
import '../../../features/authentication/screens/onBoarding/onBoarding_screen.dart';
import '../../../features/authentication/screens/signUp/verifyEmail_screen.dart';
import '../../../features/childsetup/screens/child_code_display_screen.dart';
import '../../../utils/exceptions/firebase_auth_exceptions.dart';
import '../../../utils/exceptions/firebase_exceptons.dart';
import '../../../utils/exceptions/format_exceptions.dart';
import '../../../utils/exceptions/platform_exceptions.dart';
import '../user/user_repository.dart';

class AuthenticationRepository extends GetxController{
  static AuthenticationRepository get instance => Get.find();

  final storage = GetStorage();
  final _auth = FirebaseAuth.instance;
  User? get currentUser => _auth.currentUser;

  @override
  void onReady() {
    screenRedirect();
  }


  // function to redirect to right Screen
  void screenRedirect() async{
    if(_auth.currentUser != null){
      if(_auth.currentUser!.emailVerified){


        // If verify, go to navigation menu
        await UserRepository.instance.generateChildCode(currentUser!.uid);
        //storage.write('isGenerateCode', true);
        Get.offAll(() => PermissionScreen());


        // initialize user specific box
        await GetStorage.init(currentUser!.uid);
        storage.write('currentUserId', currentUser!.uid);

      }else{
        // If not verify, go to verifyEmailScreen
        Get.offAll(() => VerifyEmailScreen(userEmail: _auth.currentUser!.email));
      }
    }else{
      storage.writeIfNull('isFirstTime', true);
      storage.read('isFirstTime') != true ? Get.offAll(() => LoginScreen()) : Get.offAll(() => OnboardingScreen());
    }

  }




  // UserRegister with Email/ Password
  Future<UserCredential> registerUser(String email, password) async{
    try{
      UserCredential userCredential = await _auth.createUserWithEmailAndPassword(email: email, password: password);
      return userCredential;
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
  }


  // Login with Email/ Password
  Future<UserCredential> loginWithEmailPassword(String email, password) async{
    try{
      UserCredential userCredential = await _auth.signInWithEmailAndPassword(email: email, password: password);
      return userCredential;
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
  }


  // Authentication with Google Account
  Future<UserCredential> signInWithGoogle() async{
    try{
      // Show all Accounts of in your phone
      final GoogleSignInAccount? googleAccount = await GoogleSignIn().signIn();
      final GoogleSignInAuthentication? googleAuth = await googleAccount?.authentication;

      // create credentials
      final OAuthCredential credential = GoogleAuthProvider.credential(
        idToken: googleAuth?.idToken,
        accessToken: googleAuth?.accessToken
      );

      // Sign in using credentials
      UserCredential userCredential = await _auth.signInWithCredential(credential);
      return userCredential;


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
  }


  // Send Email Verification
  Future<void> sendEmailVerification() async{
    try{
      await _auth.currentUser?.sendEmailVerification();
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
  }

  // Send Email for forget password
  Future<void> sendEmailForForgetPassword(String email) async{
    try{
      await _auth.sendPasswordResetEmail(email: email);
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
  }


  // Logout User Account
  Future<void> logOut() async{
    try{
      await _auth.signOut();
      await GoogleSignIn().signOut();
      Get.offAll(()=> LoginScreen());
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
  }



  // reauthenticateWithCredential by email/password
  Future<void> reAuthenticateWithCredential(String email, password) async{
    try{
      AuthCredential authCredential = EmailAuthProvider.credential(email: email, password: password);
      await currentUser!.reauthenticateWithCredential(authCredential);

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


}
