# 변경 기록

## 2.0.0+1.21.8

- Minecraft 1.21.8, Java 21, Fabric 전용 단일 프로젝트로 전환
- Forge, Architectury, Shadow, Toolbox, Transporter 의존성 제거
- 클라이언트 초기화, 렌더러, 디버그 렌더러, 키 입력, 물리 동기화 패킷 제거
- 서버 lifecycle 기반 월드별 물리 공간과 중앙 `RayonServerRuntime` 추가
- busy-spin 물리 스레드를 종료 대기와 오류 전달을 지원하는 단일 실행기로 교체
- 중복 step 요청 병합과 틱당 3회 60 Hz substep 적용
- 서버 틱 snapshot과 결과 queue를 통한 엔티티 위치·속도 적용
- 1.21.8 `ValueInput`/`ValueOutput` 기반 저장 형식 버전 2 및 1.19.4 필드 migration 추가
- `ServerExplosion`의 계산된 knockback을 Bullet impulse로 전달
- 해시 캐시, 파일 잠금, atomic move를 사용하는 Libbulletjme 네이티브 로더 추가
- Fabric lifecycle 이벤트 및 collision `VoxelShape` 기반 지형 shape로 외부 Lazurite 기능 대체
- 미로드 chunk 접근 차단, chunk unload 즉시 제거, body/tick/축별 scan budget과 지형 snapshot 성능 경고 추가
