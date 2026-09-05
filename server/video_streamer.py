import sys
import time
import struct
import cv2

def stream_video(video_url, start_sec=0, target_fps=8, max_w=240, max_h=144):
    cap = cv2.VideoCapture(video_url)
    if not cap.isOpened():
        sys.stderr.write(f"Failed to open video: {video_url}\n")
        try:
            sys.stdout.buffer.write(b'NVID')
            sys.stdout.buffer.write(struct.pack('>ii', 0, 0))
            sys.stdout.buffer.flush()
        except Exception:
            pass
        return

    fps = cap.get(cv2.CAP_PROP_FPS) or 24.0
    total_frames = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
    duration_ms = int((total_frames / fps) * 1000) if fps > 0 else 0

    # Calculate proportional aspect ratio bounded by max_w x max_h
    orig_w = cap.get(cv2.CAP_PROP_FRAME_WIDTH) or 240.0
    orig_h = cap.get(cv2.CAP_PROP_FRAME_HEIGHT) or 144.0
    if orig_w > 0 and orig_h > 0:
        aspect = float(orig_w) / float(orig_h)
        target_w = max_w
        target_h = int(round(max_w / aspect))
        if target_h > max_h:
            target_h = max_h
            target_w = int(round(max_h * aspect))
    else:
        target_w = 240
        target_h = 144
    target_w = max(2, (target_w // 2) * 2)
    target_h = max(2, (target_h // 2) * 2)

    if start_sec > 0:
        cap.set(cv2.CAP_PROP_POS_MSEC, start_sec * 1000)

    # Calculate frame skip to match target_fps
    skip = max(1, int(round(fps / target_fps)))
    frame_interval = 1.0 / target_fps

    # Header packet: [Magic 4 bytes: 'NVID'][Total duration ms: 4 bytes]
    sys.stdout.buffer.write(b'NVID')
    sys.stdout.buffer.write(struct.pack('>i', duration_ms))
    sys.stdout.buffer.flush()

    count = 0
    out_idx = 0
    start_wall_time = time.time()

    while True:
        ret = cap.grab()
        if not ret:
            break

        count += 1
        if count % skip != 0:
            continue

        ret, frame = cap.retrieve()
        if not ret or frame is None:
            continue

        current_ms = int(cap.get(cv2.CAP_PROP_POS_MSEC))

        # Resize to 240x180 QVGA with crisp INTER_AREA interpolation
        resized = cv2.resize(frame, (target_w, target_h), interpolation=cv2.INTER_AREA)

        # Compress to optimized JPEG
        _, jpg = cv2.imencode('.jpg', resized, [
            cv2.IMWRITE_JPEG_QUALITY, 50,
            cv2.IMWRITE_JPEG_OPTIMIZE, 1
        ])
        jpg_bytes = jpg.tobytes()

        # Packet: [Length: 4 bytes int][Current MS: 4 bytes int][JPEG data]
        header = struct.pack('>ii', len(jpg_bytes), current_ms)
        try:
            sys.stdout.buffer.write(header)
            sys.stdout.buffer.write(jpg_bytes)
            sys.stdout.buffer.flush()
        except (BrokenPipeError, IOError):
            break

        out_idx += 1
        expected_wall_time = start_wall_time + (out_idx * frame_interval)
        sleep_dur = expected_wall_time - time.time()
        if sleep_dur > 0:
            time.sleep(sleep_dur)

    # End of stream packet (length = 0)
    try:
        sys.stdout.buffer.write(struct.pack('>i', 0))
        sys.stdout.buffer.flush()
    except Exception:
        pass

    cap.release()

if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("Usage: python3 video_streamer.py <url> [start_sec] [fps] [max_w] [max_h]")
        sys.exit(1)

    v_url = sys.argv[1]
    s_sec = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    t_fps = int(sys.argv[3]) if len(sys.argv) > 3 else 8
    m_w = int(sys.argv[4]) if len(sys.argv) > 4 else 240
    m_h = int(sys.argv[5]) if len(sys.argv) > 5 else 144
    stream_video(v_url, s_sec, t_fps, m_w, m_h)
