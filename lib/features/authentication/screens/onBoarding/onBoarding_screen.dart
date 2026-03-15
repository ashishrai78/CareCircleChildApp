
import 'package:background/utils/constants/images.dart';
import 'package:flutter/material.dart';
import 'package:get/get.dart';
import 'package:get/get_core/src/get_main.dart';
import '../../../../common/icons/icon_widget.dart';
import '../../../../common/widgets/elevatedButton/elevated_button.dart';
import '../../../../utils/constants/colors.dart';
import '../../../../utils/constants/sizes.dart';
import '../login/login_screen.dart';
import '../signUp/signUp_Screen.dart';

class OnboardingScreen extends StatelessWidget {
  const OnboardingScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text("CareCircle", style: Theme.of(context).textTheme.headlineSmall!.apply(color: UColors.white)),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.only(bottomLeft: Radius.circular(8), bottomRight: Radius.circular(8))),
        centerTitle: true,
        backgroundColor: UColors.primary,
        elevation: 0,
      ),
      body: Padding(
        padding: EdgeInsets.all(USizes.defaultSpace),
        child: Column(
          children: [
            SizedBox(height: 50),
            Image.asset(UImages.onBodyAnimation1),

            /// SizeBox
            Spacer(),

            /// For Google SignIn
            UIconWidget(),

            /// Text
            Padding(
              padding: const EdgeInsets.all(5.0),
              child: Text('Or', style: Theme.of(context).textTheme.bodySmall,),
            ),

            //ElevatedButton
            UElevatedButton(
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(Icons.mail_outline_outlined, size: 20,),
                    SizedBox(width: 10,),
                    Text('Sign Up'),
                  ],
                ),
                onPressed: () {
                  Get.to(SignupScreen());
                },
              ),

            SizedBox(height: 10,),

            /// Have a Already an Account
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text('Already have a CareCircle account?'),
                SizedBox(width: 4,),
                GestureDetector(
                  onTap: ()=> Get.to(LoginScreen()),
                    child: Text('Sign In', style: Theme.of(context).textTheme.bodySmall!.copyWith(color: UColors.primary),)
                )
              ],
            ),

            SizedBox(height: 10,)

          ],
        ),
      ),
    );
  }
}


