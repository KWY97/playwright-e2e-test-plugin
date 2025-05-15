document.addEventListener('DOMContentLoaded', function() {
    const jsonStr = document.getElementById('modelData').textContent.trim();
    let existingModel = {};
    try { existingModel = JSON.parse(jsonStr); } catch(e) { console.error(e); }

    const editor   = document.getElementById('editor');
    const template = document.getElementById('scenario-template').querySelector('.scenario');

    function bindStep(stepElem) {
        stepElem.querySelector('.del-step')
                .addEventListener('click', () => stepElem.remove());
    }

    function bindScenario(scn) {
        scn.querySelector('.add-step')
                .addEventListener('click', () => {
                    const stepsDiv = scn.querySelector('.steps');
                    const newStep  = template.cloneNode(true)
                            .querySelector('.steps > div')
                            .cloneNode(true);
                    newStep.querySelector('input.st-text').value = '';
                    bindStep(newStep);
                    stepsDiv.appendChild(newStep);
                });
        scn.querySelector('.del-scenario')
                .addEventListener('click', () => scn.remove());
        scn.querySelectorAll('.steps > div').forEach(bindStep);
    }

    editor.querySelectorAll('.scenario').forEach(bindScenario);

    document.getElementById('addScenario')
            .addEventListener('click', () => {
                const newScn = template.cloneNode(true);
                newScn.querySelector('input.sc-title').value = '';
                newScn.querySelectorAll('input.st-text').forEach(i => i.value = '');
                editor.appendChild(newScn);
                bindScenario(newScn);
            });

    window.prepareSave = function() {
        const model = {
            title: document.getElementById('scriptTitle').value,
            scenarios: []
        };
        editor.querySelectorAll('.scenario').forEach(scn => {
            const title = scn.querySelector('input.sc-title').value;
            const steps = Array.from(
                    scn.querySelectorAll('input.st-text')
            ).map(i => i.value);
            model.scenarios.push({ title, steps });
        });
        document.getElementById('jsonData').value =
                JSON.stringify(model, null, 2);
    };

    const saveButton = document.getElementById('saveButton');
    if (saveButton) {
        saveButton.addEventListener('click', function() {
            prepareSave();
            // 버튼이 속한 form을 찾아 submit합니다.
            // saveButton.form 또는 가장 가까운 form을 찾는 로직이 필요할 수 있습니다.
            // 여기서는 saveButton이 f:form 내에 직접 있다고 가정하고,
            // f:form이 실제 HTML form 태그로 렌더링된다고 가정합니다.
            // 가장 확실한 방법은 form에 id를 부여하고 해당 id로 찾는 것입니다.
            // 우선은 간단하게 button의 form 속성을 사용해봅니다.
            if (this.form) {
                this.form.submit();
            } else {
                // Fallback: DOM 트리에서 가장 가까운 form을 찾습니다.
                let parent = this.parentNode;
                while (parent && parent.tagName !== 'FORM') {
                    parent = parent.parentNode;
                }
                if (parent) {
                    parent.submit();
                } else {
                    console.error('Save button is not inside a form.');
                }
            }
        });
    }
});
