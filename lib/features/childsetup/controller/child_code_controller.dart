import 'package:flutter/foundation.dart';
import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:get/get.dart';

class ChildCodeController extends GetxController {
  final FirebaseFirestore _firestore = FirebaseFirestore.instance;
  final FirebaseAuth _auth = FirebaseAuth.instance;

  final RxString childCode = ''.obs;
  final RxBool isLoading = true.obs;
  final RxString error = ''.obs;

  @override
  void onInit() {
    super.onInit();
    fetchChildCode();
  }

  /// Fetch the child code from Firestore using current user's UID
  Future<void> fetchChildCode() async {
    try {
      isLoading.value = true;
      error.value = '';

      final user = _auth.currentUser;
      if (user == null) {
        error.value = 'User not logged in';
        return;
      }

      final doc = await _firestore.collection('Users').doc(user.uid).get();
      if (doc.exists) {
        final data = doc.data()!;
        childCode.value = data['childCode'] ?? '';

        // 🛡️ Auto-unpair check: If paired but no parent sync for 7+ days, reset isPaired
        final bool isPaired = data['isPaired'] ?? false;
        final Timestamp? lastSync = data['lastParentSync'] as Timestamp?;
        if (isPaired && lastSync != null) {
          final days = DateTime.now().difference(lastSync.toDate()).inDays;
          if (days >= 7) {
            await _firestore.collection('Users').doc(user.uid).update({
              'isPaired': false,
              'parentUid': '',
            });
            debugPrint("⚠️ Child device automatically unlinked due to 7 days of parent inactivity");
          }
        }

        if (childCode.value.isEmpty) {
          error.value = 'No child code found';
        }
      } else {
        error.value = 'User document not found';
      }
    } catch (e) {
      error.value = 'Failed to fetch code: $e';
    } finally {
      isLoading.value = false;
    }
  }

}