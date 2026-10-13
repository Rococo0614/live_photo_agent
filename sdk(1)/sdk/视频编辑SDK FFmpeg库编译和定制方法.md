1.从ffmpeg官网下载源码包例如ffmpeg-4.0.2.tar.bz2，copy到编译服务器某一目录下；

2.下载的ffmpeg源码解压两次，分别重命名为ffmpeg-arm64和ffmpeg-armv7a，例如
tar jxvf ffmpeg-4.0.2.tar.bz2; mv ffmpeg-4.0.2 ffmpeg-arm64
tar jxvf ffmpeg-4.0.2.tar.bz2; mv ffmpeg-4.0.2 ffmpeg-armv7a

3.把附件的zip解压到ffmpeg根目录,修改tools/do-compile-ffmpeg.sh 中ANDROID_NDK 路径为自己编译服务中的NDK路径

4.在命令行中输入./compile-ffmpeg.sh all 编译

5.编译完成后生成的文件在build目录中，有ffmpeg-arm64和ffmpeg-armv7a两个文件夹
取出output/[libVideoEditorSDK_Dependency.so](http://libvideoeditorsdk_dependency.so/)和[libVideoEditorSDK_Dependency_lite.so](http://libvideoeditorsdk_dependency_lite.so/)放在SDK中使用

本编译方法参考ijkmediaplayer中ffmpeg编译方法，[https://github.com/Bilibili/ijkplayer](https://github.com/Bilibili/ijkplayer)

ffmpeg剪裁定制方法：

修改tools/module.sh 文件中配置项即可。

- 📎 [ffmpegbuildandroid.zip](https://docs.vivo.xyz/api/v1/file/weboffice/download.do?documentId=110120860176)