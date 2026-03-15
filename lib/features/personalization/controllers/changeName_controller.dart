
import 'package:background/features/personalization/controllers/user_controller.dart';
import 'package:flutter/cupertino.dart';
import 'package:get/get.dart';

import '../../../data/repositories/user/user_repository.dart';
import '../../../utils/helpers/network_manager.dart';
import '../../../utils/popups/fullScreen_loader.dart';
import '../../../utils/popups/snackbars.dart';

class ChangeNameController extends GetxController{
  static ChangeNameController get instance => Get.find();

  /// variables
  final _userController = UserController.instance;
  final fullNameForm = GlobalKey<FormState>();
  final fullName = TextEditingController();

  @override
  void onInit() {
   initializedName();
    super.onInit();
  }

  void initializedName(){
    fullName.text = _userController.user.value.name;
  }

  // Update User SingleField
  Future<void> updateUserSingleField() async{
    try{
      // Check Internet Connection
      final bool isConnect = await NetworkManager.instance.isConnected();
      if(!isConnect){
        USnackBarHelpers.errorSnackBar(title: 'No Internet!', message: 'Please check internet connection');
        return;
      }


      // FieldChecking
      if(!fullNameForm.currentState!.validate()){
        return;
      }

      // Loading
      UFullScreenLoader.openLoadingDialog('Updating...');


      // update user name
      Map<String, dynamic> map = {'name' : fullName.text};
      UserRepository.instance.updateSingleField(map);

      // Update User from RX user
      _userController.user.value.childCode = fullName.text;

      // Stop Loading
      UFullScreenLoader.stopLoading();

      // redirect Screen
      //Get.offAll(()=> NavigationMenu());

    }catch (e){
      // Stop Loading
      UFullScreenLoader.stopLoading();
      USnackBarHelpers.errorSnackBar(title: 'Fail!', message: e.toString());
    }
  }
}