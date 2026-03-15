
import 'package:flutter/material.dart';
import '../../utils/constants/texts.dart';

class UItemDividerWidget extends StatelessWidget {
  const UItemDividerWidget({
    super.key,
  });

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Expanded(child: Divider(thickness: 1, endIndent: 10, indent: 30,)),
        Text(UTexts.orSignInWith,style: Theme.of(context).textTheme.labelMedium,),
        Expanded(child: Divider(thickness: 1, endIndent: 30, indent: 10,)),
      ],
    );
  }
}