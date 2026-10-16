# 🎬 Offline AI Video Dubbing Player (AutoMkvDubber)

অফলাইন এআই ভিডিও ডাবিং প্লেয়ার — যেকোনো MKV/MP4 ভিডিও ফাইল থেকে সাবটাইটেল বের করে, Google GTX API দিয়ে বাংলায় অনুবাদ করে, Google Text-to-Speech (TTS) এবং অডিও স্পিড সিঙ্ক (atempo) প্রযুক্তির সাহায্যে স্বয়ংক্রিয়ভাবে বাংলা ডাবিং ট্র্যাক ও সাবটাইটেল তৈরি করে।

---

## 📱 কীভাবে সরাসরি APK ডাউনলোড করবেন (Direct APK Download)

### অপশন ১: সরাসরি Releases থেকে APK ডাউনলোড (সুপার সহজ - কোনো জিপ ফাইল নয়)
1. এই GitHub পেজের ডানপাশে বা উপরে থাকা **[Releases](https://github.com/jessicaalbert1234567-netizen/ban-video-player/releases)** ট্যাবে যান।
2. সর্বশেষ রিলিজের **Assets** সেকশন থেকে সরাসরি **`ban-video-player-debug.apk`** ডাউনলোড করুন।
3. আপনার অ্যান্ড্রয়েড ফোনে ফাইলটি ওপেন করে সরাসরি ইনস্টল করুন!

### অপশন ২: GitHub Actions Artifacts থেকে ডাউনলোড করলে (ZIP ফাইল)
1. GitHub Actions থেকে যে `offline-ai-dubbing-player-debug` (76 MB) ফাইলটি ডাউনলোড করেছেন, সেটি মূলত একটি **.ZIP** আর্কাইভ।
2. আপনার ফোনের ফাইল ম্যানেজার (Google Files, ZArchiver, বা My Files) দিয়ে জিপ ফাইলটি এক্সট্র্যাক্ট (Extract / Unzip) করুন।
3. ভিতরে থাকা **`app-debug.apk`** বা **`ban-video-player-debug.apk`** ফাইলটিতে ট্যাপ করে ইনস্টল করুন।

---

## ⚙️ GitHub Actions এ APK তৈরি করার নিয়ম (Automatic Build)
GitHub Actions সফলভাবে সম্পন্ন হলে স্বয়ংক্রিয়ভাবে APK তৈরি হয়। 

> **জরুরি টিপ (Releases পারমিশন সক্রিয় করা):**
> যদি GitHub Releases এ সরাসরি APK দেখতে না পান, তবে আপনার রিপোজিটরির:
> **Settings ➔ Actions ➔ General ➔ Workflow permissions ➔ "Read and write permissions"** সিলেক্ট করে Save দিন। এর পর নতুন পুশ দিলেই সরাসরি Releases পেজে সরাসরি ডাউনলোডযোগ্য APK চলে আসবে।

---

## 🌟 প্রধান ফিচারসমূহ (Key Features)
1. **MKV Track Extraction**: ভিডিও থেকে অরিজিনাল অডিও এবং সাবটাইটেল দ্রুত এক্সট্র্যাক্ট করে।
2. **Google GTX Subtitle Translator**: মাত্র কয়েক সেকেন্ডের মধ্যে পুরো ২ ঘণ্টার মুভির সাবটাইটেল নির্ভুল বাংলায় অনুবাদ করে এবং ফোনের `Download/FileTranslato/` ফোল্ডারে বাংলা `.srt` ফাইল সেভ করে।
3. **Google TTS & atempo Audio Sync**: Google Text-to-Speech দিয়ে বাংলা কণ্ঠ তৈরি করে এবং সংলাপের সময়ের সাথে মেলাতে `atempo` দিয়ে স্পিড অ্যাডজাস্ট করে নিখুঁত বাংলা ডাবিং তৈরি করে।
4. **VLC-স্টাইল ভিডিও প্লেয়ার**: উজ্জ্বলতা, ভলিউম, ১০ সেকেন্ড ফরোয়ার্ড/রিওয়াইন্ড জেসচার, সাবটাইটেল সাইজ পরিবর্তন এবং অরিজিনাল/বাংলা অডিও সুইচ করার সম্পূর্ণ প্লেয়ার।
