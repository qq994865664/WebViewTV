const style = document.createElement('style');
style.innerHTML = `
  video::-webkit-media-controls {
    display: none;
    -webkit-appearance: none;
  }
`;
document.head.appendChild(style);