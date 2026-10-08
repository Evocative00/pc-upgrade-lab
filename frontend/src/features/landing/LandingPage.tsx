import type { ReactNode } from 'react'
import { paths } from '../../lib/router.ts'
import './landing.css'

type Props = {
  // 왼쪽 탭 바 맨 아래에 놓을 로그인/회원가입(또는 로그인 상태) 영역
  auth: ReactNode
}

// 랜딩 화면: 왼쪽 탭 바 | 가운데 'PC 빌드' 버튼
export function LandingPage({ auth }: Props) {
  return (
    <div className="landing">
      <nav className="landing__tabs" aria-label="주요 메뉴">
        <a href={paths.home()} className="landing__brand">PC 업그레이드 실험실</a>
        <a href={paths.home()} aria-current="page">홈</a>
        <a href={paths.new()}>PC 구성하기</a>
        <a href={paths.list()}>내 PC</a>
        <div className="landing__auth">{auth}</div>
      </nav>

      <main className="landing__hero">
        <p className="landing__eyebrow">PC BUILDER</p>
        <h1>나만의 PC를 구성해 보세요</h1>
        <p className="landing__lead">
          부품을 고르면 호환성과 현재 상품가를 확인할 수 있습니다.<br />
          로그인 없이 시작할 수 있습니다.
        </p>
        <a href={paths.new()} className="landing__cta">PC 빌드 시작 →</a>
      </main>
    </div>
  )
}
