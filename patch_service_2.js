const fs = require('fs');
const path = './android/app/src/main/java/com/intentfirewall/NotificationService.kt';
let code = fs.readFileSync(path, 'utf8');

code = code.replace(
  /"com.samsung.android.messaging"/g,
  '"com.samsung.android.messaging",\n            "com.truecaller"'
);

code = code.replace(
  /"com.samsung.android.messaging" -> "SMS"/g,
  '"com.samsung.android.messaging", "com.truecaller" -> "SMS"'
);

fs.writeFileSync(path, code);
