// 녹화 결과로 자막 파일과 유튜브용 MP4를 만든다.
// 사용법: node e2e/make-video.mjs <출력 폴더>
import { execFileSync } from "node:child_process";
import { readFileSync, renameSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

const dir = resolve(process.argv[2]);
const { duration, frames, captions } = JSON.parse(readFileSync(resolve(dir, "timeline.json"), "utf8"));

const list = ["ffconcat version 1.0"];
frames.forEach((f, i) => {
  const next = frames[i + 1]?.t ?? duration;
  list.push(`file 'frames/${f.file}'`, `duration ${(Math.max(next - f.t, 1) / 1000).toFixed(3)}`);
});
list.push(`file 'frames/${frames.at(-1).file}'`);
writeFileSync(resolve(dir, "frames.ffconcat"), list.join("\n"));

const srtTime = (ms) => new Date(ms).toISOString().slice(11, 23).replace(".", ",");
const assTime = (ms) => new Date(ms).toISOString().slice(12, 22).replace(/^0/, "0");
writeFileSync(resolve(dir, "pumui-demo.ko.srt"), captions
  .map((c, i) => `${i + 1}\n${srtTime(c.from)} --> ${srtTime(c.to)}\n${c.text}\n`).join("\n"));

const ass = `[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
WrapStyle: 2

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Band,Noto Sans CJK KR,38,&H00FFFFFF,&H00FFFFFF,&H00000000,&H00000000,-1,0,0,0,100,100,0,0,1,0,0,5,60,60,0,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
${captions.map((c) => `Dialogue: 0,${assTime(c.from)},${assTime(c.to)},Band,,0,0,0,,{\\pos(960,1020)}${c.text.replaceAll("\n", "\\N")}`).join("\n")}
`;
writeFileSync(resolve(dir, "captions.ass"), ass);

execFileSync("ffmpeg", ["-y", "-hide_banner", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", "frames.ffconcat",
  "-vf", "fps=30,scale=1920:960,pad=1920:1080:0:0:color=0x16263a,ass=captions.ass,format=yuv420p",
  "-c:v", "libx264", "-crf", "18", "-preset", "veryfast", "-movflags", "+faststart", "-an", "pumui-demo.tmp.mp4"], { cwd: dir, stdio: "inherit" });
renameSync(resolve(dir, "pumui-demo.tmp.mp4"), resolve(dir, "pumui-demo.mp4"));
