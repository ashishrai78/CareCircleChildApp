
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/cupertino.dart';
import 'package:get/get.dart';
import '../../../../data/repositories/authentication/authentication_repository.dart';
import '../../../../data/repositories/user/user_repository.dart';
import '../../../../utils/code_generator.dart';
import '../../../../utils/helpers/network_manager.dart';
import '../../../../utils/popups/fullScreen_loader.dart';
import '../../../../utils/popups/snackbars.dart';
import '../../models/user_model.dart';
import '../../screens/signUp/verifyEmail_screen.dart';

class SignUpController extends GetxController{
  static SignUpController get instance => Get.find();

  ///Variables
  final _authRepository = Get.put(AuthenticationRepository());
  final signUpFormKey = GlobalKey<FormState>();
  final RxBool isPasswordVisible = true.obs;
  final RxBool privacyPolicy = false.obs;

  final firstName = TextEditingController();
  final email = TextEditingController();
  final password = TextEditingController();
  final phoneNumber = TextEditingController();


  /// Function to Register user with Email/Password
  Future<void> registerUser() async{
    try{

      // check Internet Connectivity
      bool isConnected = await NetworkManager.instance.isConnected();
      if(!isConnected){
        //UFullScreenLoader.stopLoading();
        USnackBarHelpers.warningSnackBar(title: 'No Internet Connection');
        return;
      }
      
      // check Privacy Policy
      if(!privacyPolicy.value){
        //UFullScreenLoader.stopLoading();
        USnackBarHelpers.warningSnackBar(title: 'Accept Privacy Policy', message: 'In order to create account, you must have to read and accept the privacy policy & Terms of use');
        return;
      }

      // Form Validation
      if(!signUpFormKey.currentState!.validate()){
        return;
      }

      UFullScreenLoader.openLoadingDialog('processing');

      // User Register with email and password from firebase
      UserCredential userCredential = await _authRepository.registerUser(email.text.trim(), password.text.trim());

      // Create user model
      AppUserModel appUser = AppUserModel(
          uid: userCredential.user!.uid,
          name: firstName.text,
          email: email.text.trim(),
          role: 'child',
          createdAt: Timestamp.now(),
          childCode: CodeGenerator.generate6DigitCode()

      );

      // Save user record
      final userRepository = Get.put(UserRepository());
      await userRepository.saveUserRecord(appUser);

      // success message
      USnackBarHelpers.successSnackBar(title: 'Congratulation!', message: 'Your account have been created \n Verify email to continue');

      UFullScreenLoader.stopLoading();

      // redirect to verify email screen
      Get.to(() => VerifyEmailScreen(userEmail: email.text,));
    }catch(e){
      // stop Loading
     // UFullScreenLoader.stopLoading();
      USnackBarHelpers.errorSnackBar(title: 'Error', message: e.toString());
    }
  }
}