import os
import sys
import uuid
import pyexiv2

def modify_gallery_identifier(video_data):
    # 新的 28 位值
    new_val = b"motionphoto00010000000000000"
    # 查找的 Key 前缀
    key_prefix = b'"com.android.camera.livephoto":"'
    new_video_data = bytearray(video_data)
    vivo_media_ext_info = b"vivoMediaExtInfo"

    try:   
        # 使用 find 定位 Key 的位置
        start_index = new_video_data.find(key_prefix)

        if start_index != -1:
            # 计算值的起始位置：Key 的索引 + Key 本身的长度
            value_start_pos = start_index + len(key_prefix)

            if value_start_pos + len(new_val) < len(video_data):
                new_video_data[value_start_pos:value_start_pos+28] = new_val
            
            print(f"   偏移地址: {hex(value_start_pos)}")
        else:
            vivo_media_ext_hex = "00 00 00 A8 75 75 69 64 76 69 76 6F 4D 65 64 69 61 45 78 74 49 6E 66 6F 76 69 76 6F 7B 22 63 6F 6D 2E 61 6E 64 72 6F 69 64 2E 63 61 6D 65 72 61 2E 6C 69 76 65 70 68 6F 74 6F 22 3A 22 6D 6F 74 69 6F 6E 70 68 6F 74 6F 30 30 30 31 30 30 30 30 30 30 30 30 30 30 30 30 30 22 2C 22 76 65 72 73 69 6F 6E 22 3A 32 31 30 38 7D 00 00 00 4E 63 61 6D 65 72 61 6C 62 75 6D 21 00 00 00 2F 6D 6F 74 69 6F 6E 70 68 6F 74 6F 30 30 30 31 30 30 30 30 30 30 30 30 30 30 30 30 30 FF FF FF FF 1B 2A 39 48 57 66 75 84 93 A2 B3"
            vivo_media_ext = bytes.fromhex(vivo_media_ext_hex)
            new_video_data = new_video_data + vivo_media_ext
 
    except Exception as e:
        print(f"⚠️ 无法处理：{e}")

    return new_video_data

def create_motion_photo(image_path, video_path, output_path):
    # 读取图片
    with open(image_path, "rb") as f:
        image_data = f.read()

    # 读取视频
    with open(video_path, "rb") as f:
        video_data = f.read()

    video_data = modify_gallery_identifier(video_data)

    video_size = len(video_data)

    # 生成 XMP Metadata
    xmp_template = f'''<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
                    <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <rdf:Description rdf:about=""
                    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                    xmlns:VCamera="http://ns.vivo.com/photos/1.0/camera/"
                    xmlns:Container="http://ns.google.com/photos/1.0/container/"
                    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
                    GCamera:MotionPhoto="1"
                    GCamera:MotionPhotoVersion="1"
                    GCamera:MotionPhotoPresentationTimestampUs="1504095"
                    VCamera:VMotionPhotoVersion="1"
                    VCamera:VMotionPhotoSource="1"
                    VCamera:VMediaKitVersion="1.0.0.5">
                    <Container:Directory>
                    <rdf:Seq>
                    <rdf:li rdf:parseType="Resource">
                    <Container:Item
                    Item:Mime="image/jpeg"
                    Item:Semantic="Primary"
                    Item:Length="0"
                    Item:Padding="0"/>
                    </rdf:li>
                    <rdf:li rdf:parseType="Resource">
                    <Container:Item
                    Item:Mime="video/mp4"
                    Item:Semantic="MotionPhoto"
                    Item:Length="{video_size}"
                    Item:Padding="0"/>
                    </rdf:li>
                    </rdf:Seq>
                    </Container:Directory>
                    </rdf:Description>
                    </rdf:RDF>
                    </x:xmpmeta>'''
    
    with pyexiv2.ImageData(image_data) as img:
        img.modify_raw_xmp(xmp_template)
        # 找到 JPEG SOI 后插入 APP1
        if image_data[0:2] != b"\xff\xd8":
            raise ValueError("输入文件不是合法 JPEG")
        new_image_data = img.get_bytes()
        print(img.read_xmp())
        # 追加视频数据
        final_data = new_image_data + video_data
        with open(output_path, "wb") as f:
            f.write(final_data)
            print("✅ Motion Photo 生成成功:")
            print("输出文件:", output_path)
            print("视频大小:", video_size)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("用法:")
        print("python make_motionphoto.py input.jpg input.mp4 output.jpg")
        sys.exit(1)

    create_motion_photo(sys.argv[1], sys.argv[2], sys.argv[3])