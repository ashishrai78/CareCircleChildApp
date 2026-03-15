
import 'package:flutter/cupertino.dart';
import 'package:get/get.dart';

import '../../../../data/repositories/authentication/authentication_repository.dart';
import '../../../../utils/helpers/network_manager.dart';
import '../../../../utils/popups/fullScreen_loader.dart';
import '../../../../utils/popups/snackbars.dart';
import '../../screens/forgetPassword/reset_password.dart';

class ForgetPasswordController extends GetxController{
  static ForgetPasswordController get instance => Get.find();

  /// Variables
  final email = TextEditingController();
  final forgetPasswordForm = GlobalKey<FormState>();

  // Function for forget password
  Future<void> sendEmailForgetPassword() async{
    try{
      // check internet connection
      final isConnect = await NetworkManager.instance.isConnected();
      if(!isConnect){
        USnackBarHelpers.errorSnackBar(title: 'No Internet!');
        return;
      }
      
      //Form validation
      if(!forgetPasswordForm.currentState!.validate()){
        return;
      }
      
      // Loading
      UFullScreenLoader.openLoadingDialog('Loading...');
      
      // for sendEmailForForgetPassword
      await AuthenticationRepository.instance.sendEmailForForgetPassword(email.text.trim());

      // stop loading
      UFullScreenLoader.stopLoading();

      //Show SnackBar
      USnackBarHelpers.successSnackBar(title: 'Send Mail', message: 'Check email, click Link for forget password');

      // Redirect
      Get.to(()=> ResetPasswordScreen(email: email.text.trim(),));

    }catch(e){
      // Screen Redirect
      UFullScreenLoader.stopLoading();
      USnackBarHelpers.errorSnackBar(title: 'Something went wrong!', message: e.toString());
    }
  }


  // Function for Resend email
  Future<void> resendSendEmailForgetPassword() async{
    try{
      // check internet connection
      final isConnect = await NetworkManager.instance.isConnected();
      if(!isConnect){
        USnackBarHelpers.errorSnackBar(title: 'No Internet!');
        return;
      }

      //Form validation
      if(!forgetPasswordForm.currentState!.validate()){
        return;
      }

      // Loading
      UFullScreenLoader.openLoadingDialog('Loading...');

      // for sendEmailForForgetPassword
      await AuthenticationRepository.instance.sendEmailForForgetPassword(email.text.trim());

      // stop loading
      UFullScreenLoader.stopLoading();

      //Show SnackBar
      USnackBarHelpers.successSnackBar(title: 'Send Mail', message: 'Check email, click Link for forget password');

    }catch(e){
      // Screen Redirect
      UFullScreenLoader.stopLoading();
      USnackBarHelpers.errorSnackBar(title: 'Something went wrong!', message: e.toString());
    }
  }
}