const fs = require('fs');
const path = './android/app/src/main/java/com/intentfirewall/NotificationService.kt';
let code = fs.readFileSync(path, 'utf8');

code = code.replace(
  /"com.google.android.apps.messaging"/g,
  '"com.google.android.apps.messaging",\n            "com.samsung.android.messaging"'
);

code = code.replace(
  /"com.android.mms", "com.google.android.apps.messaging" -> "SMS"/g,
  '"com.android.mms", "com.google.android.apps.messaging", "com.samsung.android.messaging" -> "SMS"'
);

fs.writeFileSync(path, code);
