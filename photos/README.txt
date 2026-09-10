Pictures for the photo frame (the picture button in the top bar): drop .jpg / .png / .webp files here.
Any size - they are shown full-screen on a blurred backdrop, shuffled, one every 20 s (Innstillinger -> Bilderamme).
Everything in this folder except this file is gitignored.

The page lists the folder through nginx (autoindex, see call-backend/deploy/nginx-menkerud.conf). Without nginx,
add an index.json here with the file names: ["ferie1.jpg", "bursdag.png"]

Also a fine place for mor.jpg / far.jpg (avatars, roughly square - they are cropped to circles); those can be
picked on the screen too: menu -> PIN -> Innstillinger -> Velg bilde.
