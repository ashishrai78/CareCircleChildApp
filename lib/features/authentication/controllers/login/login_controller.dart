
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/cupertino.dart';
import 'package:get/get.dart';
import 'package:get_storage/get_storage.dart';
import '../../../../data/repositories/authentication/authentication_repository.dart';
import '../../../../utils/constants/keys.dart';
import '../../../../utils/helpers/network_manager.dart';
import '../../../../utils/popups/fullScreen_loader.dart';
import '../../../../utils/popups/snackbars.dart';

import '../../../personalization/controllers/user_controller.dart';

class LoginController extends GetxController{
  static LoginController get instance => Get.find();
  final controller = Get.put(UserController());

  /// Variables
  final email = TextEditingController();
  final password = TextEditingController();

  final localStorage = GetStorage();

  final loginFormKey = GlobalKey<FormState>();
  RxBool isPasswordVisible = true.obs;
  RxBool isRememberMe = false.obs;

  @override
  void onInit() {
    email.text = localStorage.read(UKeys.rememberEmail) ?? '';
    password.text = localStorage.read(UKeys.rememberPassword) ?? '';
    super.onInit();
  }

  // Function fo login with email and password
  Future<void> loginWithEmailPassword() async{
    try{
      // check Internet Connectivity
      final isConnect = await NetworkManager.instance.isConnected();
      if(!isConnect){
        USnackBarHelpers.errorSnackBar(title: 'No Internet Connection!');
        return;
      }

      // Form validation
      if(!loginFormKey.currentState!.validate()){
        return;
      }

      // Save data if Remember me
      if(isRememberMe.value){
        localStorage.write(UKeys.rememberEmail, email.text.trim());
        localStorage.write(UKeys.rememberPassword, password.text.trim());
      }

      // Loading Screen
      UFullScreenLoader.openLoadingDialog('Log In...');

      // login with email and password
      await AuthenticationRepository.instance.loginWithEmailPassword(email.text.trim(), password.text.trim());

      // Loading Screen stop
      UFullScreenLoader.stopLoading();

      // Redirect Screen
      AuthenticationRepository.instance.screenRedirect();


      // Login Successful snackBar
      USnackBarHelpers.successSnackBar(title: 'Login Successful');

    }catch(e){
      // Loading Screen stop
      UFullScreenLoader.stopLoading();
      USnackBarHelpers.errorSnackBar(title: 'Login fail', message: e.toString());
    }
  }


  // Authentication with Google Account
  Future<void> googleSignIn() async{
    try{
      // check internet connection
      final isConnect = await NetworkManager.instance.isConnected();
      if(!isConnect){
        USnackBarHelpers.errorSnackBar(title: 'No Internet!');
        return;
      }

      // Loading Screen
      UFullScreenLoader.openLoadingDialog('You Log in...');

      // authentication with GoogleAccount
      UserCredential userCredential = await AuthenticationRepository.instance.signInWithGoogle();

      // save user credential
      controller.saveUserRecord(userCredential);

      // stop Loading
      UFullScreenLoader.stopLoading();

      //Screen Redirect
      AuthenticationRepository.instance.screenRedirect();

    }catch (e){
      UFullScreenLoader.stopLoading();
      USnackBarHelpers.errorSnackBar(title: 'Error', message: e.toString());
    }
  }
}