
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:get/get.dart';
import '../../../common/widgets/custom_shape/roundedBorder_container.dart';
import '../controller/child_code_controller.dart';
import '../../../utils/constants/colors.dart';
import '../../../utils/constants/sizes.dart';
import '../controller/permission_controller.dart';

class ChildCodeDisplayScreen extends StatelessWidget {
  const ChildCodeDisplayScreen({super.key});

  @override
  Widget build(BuildContext context) {
    const MethodChannel _audioChannel = MethodChannel('carecircle/audio');
    final controller = Get.put(ChildCodeController());

    return Scaffold(
      appBar: AppBar(
        title: Text("Binding Code", style: Theme.of(context).textTheme.headlineSmall!.apply(color: UColors.white)),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.only(bottomLeft: Radius.circular(8), bottomRight: Radius.circular(8))),
        centerTitle: true,
        backgroundColor: UColors.primary,
        elevation: 0,
      ),
      body: Padding(
        padding: const EdgeInsets.all(USizes.defaultSpace),
        child: Center(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // Title
              const Text('Share this code with parent',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold,),
                textAlign: TextAlign.center,
              ),

              const SizedBox(height: 8),

              const Text('Parent can use this code to link your device',
                style: TextStyle(fontSize: 14, color: Colors.grey,),
                textAlign: TextAlign.center,
              ),

              const SizedBox(height: 40),

              // Code display card with loading/error handling
              Obx(() {
                if (controller.isLoading.value) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (controller.error.isNotEmpty) {
                  return Container(
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      color: Colors.red.shade50,
                      borderRadius: BorderRadius.circular(16),
                    ),
                    child: Column(
                      children: [
                        Icon(Icons.error_outline, color: Colors.red, size: 50),
                        const SizedBox(height: 10),
                        Text(controller.error.value,
                          style: const TextStyle(color: Colors.red),
                          textAlign: TextAlign.center,
                        ),
                        const SizedBox(height: 10),
                        ElevatedButton(
                          onPressed: controller.fetchChildCode,
                          child: const Text('Retry'),
                        ),
                      ],
                    ),
                  );
                }

                return URoundedContainer(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(20),
                  border: true,
                  child: Padding(
                    padding: const EdgeInsets.all(USizes.lg),
                    child: Column(
                      children: [
                        const Text('Your Code',
                          style: TextStyle(fontSize: 16, color: Colors.grey,),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          controller.childCode.value,
                          style: const TextStyle(
                            fontSize: 40,
                            fontWeight: FontWeight.bold,
                            letterSpacing: 8,
                            color: UColors.primary,
                          ),
                        ),
                        const SizedBox(height: 16),
                      ],
                    ),
                  ),
                );
              }),
              const SizedBox(height: 30),

              // Instructions
              const Text(
                'Instructions:',
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: 8),
              const Text(
                    '1. Ask your parent to open their app\n'
                    '2. They will enter this code to link\n'
                    '3. Get linked this device',
                style: TextStyle(
                  fontSize: 14,
                  color: Colors.grey,
                  height: 1.5,
                ),
              ),
              
              ////  =========Mic============
              SizedBox(height: 10,),
              ElevatedButton(onPressed: () async{
                await Get.put(SetupController()).requestMicPermission();
              }, child: Text('Mic Permission')),

              SizedBox(height: 15,),
          ElevatedButton(
            onPressed: () async {
              await _audioChannel.invokeMethod("resetAudioRoute");
            },
            child: Text("Reset Audio"),
          )
            ],
          ),
        ),
      ),
    );
  }
}