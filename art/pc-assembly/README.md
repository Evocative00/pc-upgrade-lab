# PC 조립 장면 v4 재현

`build_scene.py`가 자체 제작한 블랙·실버 PC 장면과 조립 동작을 작성한다. 실제 제품의 형태 참고는 `references.json`에 기록하며, 특정 제품의 정밀 CAD 모델을 제공하는 작업은 아니다. 아래 명령은 프로젝트 루트의 PowerShell에서 실행한다. DB와 `bootRun`은 필요 없다.

Git에는 최종 v4 원본·정지 이미지·42장 검토 프레임과 홈페이지용 WebP를 포함한다. 팀원은 pull 후 프론트엔드만 실행해도 조립 장면을 볼 수 있으며, Blender 렌더를 다시 할 필요는 없다. 이전 v1–v3의 `.blend`·렌더 이미지·검토 페이지는 제작 PC의 로컬 이력으로 보존하고 Git에서 제외한다. `build_scene-v2.py`, `build_scene-v3.py`, 이전 규격·참고 JSON은 제작 이력 자료이며, 그 안의 이전 산출물 경로는 저장소에 포함된 파일을 뜻하지 않는다.

## 1. 원본 장면과 검토 이미지

Blender 4.5 LTS와 WebP를 지원하는 Pillow가 설치된 Python을 준비한다. 이 PC의 포터블 Blender 경로 예시는 다음과 같다. 다른 환경에서는 실행 파일 경로만 바꾼다.

```powershell
$blender = '.\backend\build\pc-assembly-runtime\blender-4.5.14-windows-x64\blender.exe'
$python = 'python' # Pillow + WebP 지원 Python 실행 파일 또는 절대 경로
$artDir = '.\art\pc-assembly'
$masterDir = '.\backend\build\pc-assembly-web-v4'

& $blender --background --python-exit-code 1 --python "$artDir\build_scene.py" -- --output-dir $artDir --resolution 2560 --samples 128 --render-stills --render-details --render-preview
if ($LASTEXITCODE -ne 0) { throw 'Blender authoring failed' }
```

- `pc-assembly-v4.blend`: 저장한 원본 장면, 1–240 프레임의 조립→분해→조립 동작.
- `assembled-v4.png`, `exploded-v4.png`: 2560×1920 정지 이미지.
- `motherboard-detail-v4.png`, `cooler-detail-v4.png`: 1600×1200 근접 이미지. 촬영 후 전체 장면의 카메라·가시성·설정을 복원한다.
- `preview-frames-v4/`와 `preview-manifest.json`: 800×600의 42장 검토용 슬라이더 이미지. 홈페이지용 480단계 자료와 별개다.
- `render-metadata-v4.json`: 장면·렌더 설정과 검토 기록. 렌더 플래그 없이 실행해도 `.blend`와 이 메타데이터를 작성한다.

필요한 산출물만 만들려면 `--render-stills`, `--render-details`, `--render-preview`를 각각 선택한다. 실제 사용 장치와 샘플 수는 메타데이터에서 확인한다. 원본 장면을 다시 작성하면 아래 렌더 재개에 사용하는 장면 해시도 바뀔 수 있다.

## 2. 저장한 `.blend`에서 촘촘한 웹 원본 렌더

```powershell
& $blender --background "$artDir\pc-assembly-v4.blend" --python-exit-code 1 --python "$artDir\render_web_sequence.py" -- --output-dir $masterDir --resolution 1600 --samples 64 --steps 480
if ($LASTEXITCODE -ne 0) { throw 'Dense sequence render failed' }
```

`render_web_sequence.py`는 원본 동작을 소수 프레임 위치에서 평가한다. 480개 타임라인 위치는 전반부 240개 고유 포즈와 정확한 역순 포즈를 연결한다. 역방향 변환 일치와 카메라 범위를 먼저 검사한 뒤, 후반부는 같은 픽셀을 재사용한다. 결과는 1600×1200 무손실 PNG `pose-0000.png`–`pose-0239.png`, `render-config.json`, `web-render-metadata.json`이다. 이 검사가 모든 프레임의 물리적 충돌을 검증하는 것은 아니다.

중단한 동일 작업만 명시적으로 재개한다.

```powershell
& $blender --background "$artDir\pc-assembly-v4.blend" --python-exit-code 1 --python "$artDir\render_web_sequence.py" -- --output-dir $masterDir --resolution 1600 --samples 64 --steps 480 --resume
if ($LASTEXITCODE -ne 0) { throw 'Dense sequence resume failed' }
```

`--resume`은 원본 `.blend` SHA-256과 해상도·샘플 수·단계 수가 기존 설정과 같을 때만 허용한다. 장면이나 설정을 바꿨다면 새 원본 출력 폴더를 지정한다. 완료 여부는 `web-render-metadata.json`의 `complete`에서 확인한다. 포터블 런타임, GPU 렌더 기록, 무손실 시퀀스 원본은 Git에서 제외된 `backend/build/`에 둔다.

현재 공유본은 저장 경로를 상대경로로 바꾸고 파일 탐색기 디렉터리를 비웠으며, 형상·카메라·재질·동작은 동일하다. `scene-spec.json`의 `authoredScene.sha256`은 공유본 해시이고, `authoredScene.renderSourceSceneSha256`과 기존 웹 검증 해시는 렌더 당시 원본 해시다. 기존 master를 이 공유본으로 `--resume`할 수는 없으므로, 다시 렌더할 때는 새 master 폴더를 지정한다.

## 3. Pillow로 웹 전달 자료 만들기

```powershell
& $python "$artDir\prepare_web_assets.py" --master-dir $masterDir --public-dir '.\frontend\public\pc-assembly' --art-dir $artDir
if ($LASTEXITCODE -ne 0) { throw 'Web asset preparation failed' }
```

변환기는 240개 고유 원본의 완료 상태를 확인하고 크기 조절·WebP 인코딩을 수행한다. `frontend/public/pc-assembly/`에 다음을 작성한다.

- `desktop/pose-*.webp`: 1600×1200, `mobile/pose-*.webp`: 960×720. 각각 240장.
- `poster-desktop.webp`, `poster-mobile.webp`: 완성 장면의 기본 이미지.
- `motherboard-detail-v4.webp`, `cooler-detail-v4.webp`: 근접 PNG가 있을 때 변환하는 1600×1200 이미지.
- `manifest.json`: variant 규격, 고유 포즈 경로, `[0…239, 239…0]`의 480단계 매핑, 공식 형태 참고 링크. 변환 마지막에 교체한다.

`art/pc-assembly/web-delivery-v4.json`은 전달 용량·원본 렌더 설정·검사 결과를 기록한다. 홈페이지는 `/pc-assembly/manifest.json`을 읽어 스크롤 위치에 해당하는 이미지를 그린다. `fps: 60`과 8초는 자료의 명목상 시간 기준이며, 480단계라는 수가 기기에서 고정 60fps나 매끄러운 재생을 보장하지는 않는다. 렌더 완료·이미지 품질·실제 브라우저 성능은 산출물과 실행 결과로 별도 확인한다.

렌더를 이미 실행 중이라면 변환 명령에 `--follow-render`를 추가해 저장 완료된 PNG부터 병렬로 변환할 수 있다. 원본 설정이 도중에 바뀌면 중단하며, 240장 전체의 완료를 확인하기 전에는 홈페이지 manifest를 발행하지 않는다.
