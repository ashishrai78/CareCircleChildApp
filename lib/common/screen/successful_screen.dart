
import 'package:flutter/material.dart';

import '../../utils/constants/sizes.dart';
import '../../utils/helpers/device_helper.dart';
import '../widgets/elevatedButton/elevated_button.dart';

class SuccessfulScreen extends StatelessWidget {
  const SuccessfulScreen({super.key, required this.title, required this.subTitle, required this.image, required this.onTap});

  final String title, subTitle, image;
  final VoidCallback onTap;
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(automaticallyImplyLeading: false,
      ),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(USizes.defaultSpace),
          child: Column(
            children: [
              //-----[sendEmailImage]-----
              Image.asset(image, height: UDeviceHelper.getScreenWidth(context)*0.7,),

              ///---[header]----
              SizedBox(height: USizes.spaceBtwSections,),
              Text(title, style: Theme.of(context).textTheme.headlineMedium, textAlign: TextAlign.center,),


              //----[title]----
              SizedBox(height: USizes.spaceBtwItems,),
              Text(subTitle, style: Theme.of(context).textTheme.bodyLarge, textAlign: TextAlign.center,),

              //----[Button]----
              SizedBox(height: USizes.spaceBtwSections,),
              UElevatedButton(onPressed: onTap, child: Text('Done')),

            ],
          ),
        ),
      ),
    );
  }
}
