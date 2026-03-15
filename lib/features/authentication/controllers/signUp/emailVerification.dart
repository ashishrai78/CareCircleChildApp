import 'dart:async';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:get/get.dart';

import '../../../../common/screen/successful_screen.dart';
import '../../../../data/repositories/authentication/authentication_repository.dart';
import '../../../../utils/constants/images.dart';
import '../../../../utils/constants/texts.dart';
import '../../../../utils/popups/snackbars.dart';

class EmailVerification extends GetxController{
  static EmailVerification get instance => Get.find();

  final _auth = FirebaseAuth.instance;


  /// Variables
  ///
  @override
  void onInit() {
    sendEmailVerification();
    setTimeForAutoRedirect();
    super.onInit();
  }

  // Email Verification
  Future<void> sendEmailVerification() async{
    try{
      await AuthenticationRepository.instance.sendEmailVerification();
      USnackBarHelpers.successSnackBar(title: 'Mail sent', message: 'Verify email address');
    }catch (e){
      USnackBarHelpers.errorSnackBar(title: 'Error', message: e.toString());
    }
  }

  // Auto Redirect
  Future<void> setTimeForAutoRedirect() async{
    Timer.periodic(Duration(seconds: 1), (timer) async{
      await FirebaseAuth.instance.currentUser!.reload();
      final user = FirebaseAuth.instance.currentUser;
      if(user?.emailVerified ?? false){
        timer.cancel();
        Get.off(()=>
        SuccessfulScreen(
            title: UTexts.accountCreatedTitle,
            subTitle: UTexts.accountCreatedSubTitle,
            image: UImages.successfulPaymentIcon,
            onTap: AuthenticationRepository.instance.screenRedirect
        )
        );
      }
    });
  }

  // Manual Redirect
  Future<void> checkEmailVerificationStatus() async{
    try{
      final currentUser = FirebaseAuth.instance.currentUser;
      if(currentUser != null && currentUser.emailVerified){
        Get.off(()=>
            SuccessfulScreen(
                title: UTexts.accountCreatedTitle,
                subTitle: UTexts.accountCreatedSubTitle,
                image: UImages.successfulPaymentIcon,
                onTap: AuthenticationRepository.instance.screenRedirect
            )
        );
      }

    }catch(e){

    }
  }
}