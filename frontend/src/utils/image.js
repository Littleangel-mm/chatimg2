const MAX_SIZE = 1024
const QUALITY = 0.82

export function dataUrlToFile(dataUrl, baseName = 'source') {
  const match = /^data:(image\/(?:jpeg|png|webp));base64,(.+)$/i.exec(dataUrl || '')
  if (!match) throw new Error('参考图片数据无效，请重新上传')

  const mediaType = match[1].toLowerCase()
  const binary = atob(match[2])
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.charCodeAt(i)
  }
  const extension = mediaType === 'image/jpeg' ? 'jpg' : mediaType.split('/')[1]
  return new File([bytes], `${baseName}.${extension}`, { type: mediaType })
}

export function compressImage(file) {
  return new Promise((resolve, reject) => {
    if (!file) {
      reject(new Error('未获取到图片数据'))
      return
    }

    const reader = new FileReader()
    reader.onload = (e) => {
      const img = new Image()
      img.onload = () => {
        let { width, height } = img
        if (!width || !height) {
          reject(new Error('无法读取图片尺寸'))
          return
        }

        if (width > MAX_SIZE || height > MAX_SIZE) {
          if (width >= height) {
            height = Math.round((height / width) * MAX_SIZE)
            width = MAX_SIZE
          } else {
            width = Math.round((width / height) * MAX_SIZE)
            height = MAX_SIZE
          }
        }

        const canvas = document.createElement('canvas')
        canvas.width = width
        canvas.height = height
        const ctx = canvas.getContext('2d')
        if (!ctx) {
          reject(new Error('浏览器不支持图片处理'))
          return
        }

        ctx.drawImage(img, 0, 0, width, height)
        resolve(canvas.toDataURL('image/jpeg', QUALITY))
      }
      img.onerror = () => reject(new Error('图片解码失败'))
      img.src = e.target?.result
    }
    reader.onerror = () => reject(new Error('图片读取失败'))
    reader.readAsDataURL(file)
  })
}
