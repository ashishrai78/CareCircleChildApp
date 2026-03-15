
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import '../../../data/repositories/authentication/authentication_repository.dart';
import '../../../data/repositories/user/user_repository.dart';
import '../../../utils/code_generator.dart';
import '../../../utils/helpers/network_manager.dart';
import '../../../utils/popups/fullScreen_loader.dart';
import '../../../utils/popups/snackbars.dart';
import '../../authentication/models/user_model.dart';
import '../../authentication/screens/login/login_screen.dart';

class UserController extends GetxController {
  static UserController get instance => Get.find();

  /// Variables
  final controller = Get.put(UserRepository());
  Rx<AppUserModel> user = AppUserModel.empty().obs;
  RxBool profileLoading = false.obs;
  RxBool isProfileUploading = false.obs;

  /// Re-Authentication Variables
  final email = TextEditingController();
  final password = TextEditingController();
  RxBool isShowPassword = true.obs;
  final verifyForm = GlobalKey<FormState>();

  @override
  void onInit() {
    fetchUserDetails();
    super.onInit();
  }

  // for save User Details
  Future<void> saveUserRecord(UserCredential userCredential) async {
    try {
      await fetchUserDetails();
      if(user.value.uid.isEmpty){
        // Create UserModel
        AppUserModel appUser = AppUserModel(
            uid: userCredential.user!.uid,
            name: userCredential.user!.displayName.toString(),
            email: userCredential.user!.email ?? '',
            role: 'child',
            createdAt: Timestamp.now(),
            childCode: CodeGenerator.generate6DigitCode().toString()
        );

        // repository for save userDetail
        await controller.saveUserRecord(appUser);
      }

    } catch (e) {
      USnackBarHelpers.errorSnackBar(
          title: 'No Save Data!', message: 'Something want wrong');
    }
  }

  // Read/FetchUserData from firebase
  Future<void> fetchUserDetails() async {
    try {
      profileLoading.value = true;
      AppUserModel user = await UserRepository.instance.fetchUserDetails();
      this.user.value = user;
    } catch (e) {
      user.value = AppUserModel.empty();
    } finally {
      profileLoading.value = false;
    }
  }

  // Function for delete Account Warning PopUp
  void deleteAccountWarningPopup() {
    Get.defaultDialog(
      contentPadding: EdgeInsets.all(20),
        title: 'Delete Account',
        middleText: 'Are you sure want to delete account permanently?',
        cancel:
            OutlinedButton(onPressed: () => Get.back(), child: Text('Cancel')),
        confirm:
            OutlinedButton(onPressed: deleteUserAccount,
                style: ElevatedButton.styleFrom(backgroundColor: Colors.red),child: Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 15),
                  child: Text('Delete', style: TextStyle(color: Colors.white),),)
            )
    );
  }


  // 1st re-Authentication User
  // Function for delete Account
  Future<void> deleteUserAccount() async{
    try{
      // Loading
      UFullScreenLoader.openLoadingDialog('Processing...');


      final authRepository = AuthenticationRepository.instance;
      final provider = authRepository.currentUser!.providerData.map((e) => e.providerId).first;

      // If Google provider
      if(provider == 'google.com'){
        await authRepository.signInWithGoogle();
        //await authRepository.deleteUserAccount();
        // Stop Loading
        UFullScreenLoader.stopLoading();
        Get.offAll(()=> LoginScreen());

        // If Email/Password Provider
      }else if(provider == 'password'){
        // Stop Loading
        UFullScreenLoader.stopLoading();
        //Get.to(()=> ReAuthenticationUser());
      }

    }catch (e){
      // Stop Loading
      UFullScreenLoader.stopLoading();
      USnackBarHelpers.errorSnackBar(title: 'Error', message: e.toString());
    }
  }


  // Re-Authentication Function
  Future<void> reAuthenticationUser() async{
    try{
      // Check Internet Connection
      final isConnect = await NetworkManager.instance.isConnected();
      if(!isConnect){
        USnackBarHelpers.errorSnackBar(title: 'No Internet!', message: 'Please check internet connection');
        return;
      }


      // Text fields Checking
      if(!verifyForm.currentState!.validate()){
        return;
      }

      // Loading
      UFullScreenLoader.openLoadingDialog('Processing...');


      // Re-Authentication
      await AuthenticationRepository.instance.reAuthenticateWithCredential(email.text.trim(), password.text.trim());
      // Delete User account
      //await AuthenticationRepository.instance.deleteUserAccount();

      // Stop loading
      UFullScreenLoader.stopLoading();

      // Screen Redirect
      Get.offAll(()=> LoginScreen());

      
    }catch (e){
      // Stop loading
      UFullScreenLoader.stopLoading();
      USnackBarHelpers.errorSnackBar(title: 'error', message: e.toString());
    }
  }


}
