import { useRef, type ReactNode } from 'react'
import { paths } from '../../lib/router.ts'
import { PcAssemblyScroll } from './PcAssemblyScroll.tsx'
import './landing.css'

type Props = {
  // 왼쪽 탭 바 맨 아래에 놓을 로그인/회원가입(또는 로그인 상태) 영역
  auth: ReactNode
}

// 랜딩 화면: 왼쪽 탭 바 | 가운데 'PC 빌드' 버튼
export function LandingPage({ auth }: Props) {
  const scrollRoot = useRef<HTMLDivElement>(null)
  return (
    <div className="landing" ref={scrollRoot}>
      <nav className="landing__tabs" aria-label="주요 메뉴">
        <a href={paths.home()} className="landing__brand">PC 업그레이드 실험실</a>
        <a href={paths.home()} aria-current="page">홈</a>
        <a href={paths.new()}>PC 구성하기</a>
        <a href={paths.list()}>내 PC</a>
        <div className="landing__auth">{auth}</div>
      </nav>

      <main className="landing__hero">
        <PcAssemblyScroll scrollRoot={scrollRoot}>
          <p className="landing__eyebrow">BUILD YOUR NEXT PC</p>
          <h1>나만의 PC를,<br />더 선명하게.</h1>
          <p className="landing__lead">부품 하나부터 다음 업그레이드까지.<br />호환성과 현재 상품가를 확인하고<br />로그인 없이 구성을 시작하세요.</p>
          <a href={paths.new()} className="landing__cta">PC 빌드 시작 →</a>
        </PcAssemblyScroll>
      </main>
    </div>
  )
}
