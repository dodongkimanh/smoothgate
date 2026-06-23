import { NavLink, useLocation } from 'react-router-dom'
import {
  LayoutDashboard,
  Megaphone,
  ShoppingCart,
  Link2,
  Package,
  Settings,
  Users,
  Bot,
} from 'lucide-react'
import logoMark from '../assets/smoothgate-mark.svg'

const navItems = [
  { path: '/dashboard', icon: LayoutDashboard, label: 'Tổng quan' },
  { path: '/campaigns-list', icon: Megaphone, label: 'Trình quản lý QC' },
  { path: '/orders', icon: ShoppingCart, label: 'Đơn hàng' },
  { path: '/connect-ads', icon: Link2, label: 'Kết nối QC' },
  { path: '/connect-poscake', icon: Package, label: 'Kết nối Poscake' },
  { path: '/ad-accounts', icon: Users, label: 'Tài khoản QC' },
]

const bottomItems = [
  { path: '/agent', icon: Bot, label: 'Agent AI' },
  { path: '/settings', icon: Settings, label: 'Cài đặt' },
]

export default function Sidebar({ onClose }) {
  const location = useLocation()

  return (
    <aside className="w-[76px] bg-white/95 backdrop-blur-sm border-r border-slate-200 flex flex-col items-center py-4 shadow-sm min-h-screen">
      {/* Logo */}
      <div className="mb-6 p-1 rounded-2xl bg-gradient-to-b from-slate-100 to-slate-50 border border-slate-200 animate-float-soft">
        <img src={logoMark} alt="SmoothGate" className="w-11 h-11 rounded-xl shadow-lg shadow-blue-500/20 object-contain" />
      </div>

      {/* Main Navigation */}
      <nav className="flex-1 flex flex-col items-center gap-1.5 px-2">
        {navItems.map((item) => {
          const Icon = item.icon
          const isActive = location.pathname === item.path ||
            (item.path === '/campaigns-list' && ['/campaigns', '/campaigns-list', '/ad-groups', '/posts'].includes(location.pathname))
          return (
            <NavLink
              key={item.path}
              to={item.path}
              onClick={onClose}
              className={`group relative w-12 h-12 flex items-center justify-center rounded-xl transition-all duration-200 hover:-translate-y-[1px] ${
                isActive
                  ? 'bg-blue-500 text-white shadow-lg shadow-blue-500/30'
                  : 'text-gray-400 hover:bg-gray-50 hover:text-gray-600'
              }`}
              title={item.label}
            >
              <Icon size={20} strokeWidth={isActive ? 2.5 : 1.8} />
            </NavLink>
          )
        })}
      </nav>

      {/* Separator */}
      <div className="w-8 h-px bg-gray-200 my-2"></div>

      {/* Bottom Navigation */}
      <div className="flex flex-col items-center gap-1.5 px-2">
        {bottomItems.map((item) => {
          const Icon = item.icon
          const isActive = location.pathname === item.path
          return (
            <NavLink
              key={item.path}
              to={item.path}
              onClick={onClose}
              className={`group relative w-12 h-12 flex items-center justify-center rounded-xl transition-all duration-200 hover:-translate-y-[1px] ${
                isActive
                  ? 'bg-blue-500 text-white shadow-lg shadow-blue-500/30'
                  : 'text-gray-400 hover:bg-gray-50 hover:text-gray-600'
              }`}
              title={item.label}
            >
              <Icon size={20} strokeWidth={isActive ? 2.5 : 1.8} />
            </NavLink>
          )
        })}
      </div>

      {/* Brand */}
      <div className="mt-3 pt-3 border-t border-gray-100 flex flex-col items-center">
        <span className="text-[10px] font-bold tracking-wide text-slate-500">SG</span>
      </div>
    </aside>
  )
}
