import 'package:cloud_firestore/cloud_firestore.dart';

class AppUserModel {
  final String uid;
  final String email;
  String name;
  final String role; // parent | child
  bool? emailVerified;
  final bool isPaired;
  String childCode;
  String parentUid;
  List<String> linkedChildren;
  Timestamp? createdAt;

  AppUserModel({
    required this.uid,
    required this.email,
    required this.name,
    required this.role,
    this.emailVerified,
    this.isPaired = false,
    this.createdAt,
    required this.childCode,
    this.parentUid = '',
    List<String>? linkedChildren,
  }) : linkedChildren = linkedChildren ?? [];

  /// EMPTY
  static AppUserModel empty() => AppUserModel(
    uid: '',
    email: '',
    name: '',
    role: '',
    childCode: '',
    emailVerified: false,
    isPaired: false,
    createdAt: Timestamp.now(),
  );

  /// TO FIRESTORE
  Map<String, dynamic> toJson() {
    return {
      'email': email,
      'name': name,
      'role': role,
      'emailVerified': emailVerified,
      'isPaired': isPaired,
      'childCode': childCode,
      'parentUid': parentUid,
      'linkedChildren': linkedChildren,
      'createdAt': createdAt,
    };
  }

  /// FROM FIRESTORE
  factory AppUserModel.fromSnapshot(
      DocumentSnapshot<Map<String, dynamic>> doc) {
    final data = doc.data();
    if (data == null) return AppUserModel.empty();

    return AppUserModel(
      uid: doc.id,
      email: data['email'] ?? '',
      name: data['name'] ?? '',
      role: data['role'] ?? '',
      emailVerified: data['emailVerified'] ?? false,
      isPaired: data['isPaired'] ?? false,
      childCode: data['childCode'] ?? '',
      parentUid: data['parentUid'] ?? '',
      linkedChildren: List<String>.from(data['linkedChildren'] ?? []),
      createdAt: data['createdAt'] ?? Timestamp.now(),
    );
  }
}
