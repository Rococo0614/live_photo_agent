import sys
import os

def extract_mp4_by_ftyp(input_path):
    # MP4 文件头的 ftyp 标识，通常是文件开头的第 4 到第 7 个字节
    # 但在 Motion Photo 中，它可能出现在文件的任何位置。
    FTYP_MAGIC = b'ftyp'

    # 输出文件名为 '原始文件名_extracted.mp4'
    base, ext = os.path.splitext(input_path)
    output_path = base + "_extracted.mp4"

    try:
        with open(input_path, 'rb') as f:
            data = f.read()

            # 搜索 ftyp 字节序列
            offset = data.find(FTYP_MAGIC)

            if offset == -1:
                print(f"❌ 错误: 在文件 '{input_path}' 中找不到 MP4 头部标识 'ftyp'。")
                return

            # 从找到的偏移量开始截取到文件末尾
            mp4_data = data[offset - 4:] # 通常 ftyp 前面还有 4 个字节的 size 字段

            # 再次检查截取后的数据是否真的以 'ftyp' 标记前的 4 个字节 (size) 开始
            # 确保我们截取的是一个完整的 MP4 box
            if len(mp4_data) < 8 or mp4_data[4:8] != FTYP_MAGIC:
                 # 如果找不到完整的 box 结构，尝试直接从 ftyp 开始
                mp4_data = data[offset:]
                print(f"⚠️ 警告: 未找到完整的 MP4 Box 结构，从 'ftyp' 偏移量 {offset} 开始截取。")
            else:
                print(f"✅ 成功: 在偏移量 {offset - 4} 处找到 MP4 Box。")

            with open(output_path, 'wb') as out_f:
                out_f.write(mp4_data)

            print(f"🎉 提取成功! MP4 视频已保存到: '{output_path}'")

    except Exception as e:
        print(f"致命错误: 处理文件 '{input_path}' 时发生异常: {e}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("用法: python extract_mp4.py <MotionPhoto 文件路径>")
        sys.exit(1)

    for i in range(len(sys.argv) - 1):
        extract_mp4_by_ftyp(sys.argv[i + 1])